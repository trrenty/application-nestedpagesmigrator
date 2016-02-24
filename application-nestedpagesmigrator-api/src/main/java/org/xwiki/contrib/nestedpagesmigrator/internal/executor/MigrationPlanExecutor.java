/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.contrib.nestedpagesmigrator.internal.executor;

import java.util.Arrays;
import java.util.Date;
import java.util.concurrent.ThreadLocalRandom;

import javax.inject.Inject;
import javax.inject.Provider;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.xwiki.bridge.DocumentAccessBridge;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.annotation.InstantiationStrategy;
import org.xwiki.component.descriptor.ComponentInstantiationStrategy;
import org.xwiki.contrib.nestedpagesmigrator.MigrationAction;
import org.xwiki.contrib.nestedpagesmigrator.MigrationConfiguration;
import org.xwiki.contrib.nestedpagesmigrator.MigrationException;
import org.xwiki.contrib.nestedpagesmigrator.MigrationPlanTree;
import org.xwiki.contrib.nestedpagesmigrator.Preference;
import org.xwiki.contrib.nestedpagesmigrator.Right;
import org.xwiki.job.JobExecutor;
import org.xwiki.job.event.status.JobProgressManager;
import org.xwiki.model.EntityType;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.model.reference.SpaceReference;
import org.xwiki.refactoring.job.MoveRequest;
import org.xwiki.refactoring.job.RefactoringJobs;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

/**
 * Component that execute a plan, according to the configuration.
 * This component is not thread-safe.
 *
 * @version $Id: $
 * @since 0.4
 */
@Component(roles = MigrationPlanExecutor.class)
@InstantiationStrategy(ComponentInstantiationStrategy.PER_LOOKUP)
public class MigrationPlanExecutor
{
    @Inject
    private JobProgressManager progressManager;

    @Inject
    private JobExecutor jobExecutor;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private EntityReferenceSerializer<String> serializer;

    @Inject
    private DocumentAccessBridge documentAccessBridge;

    @Inject
    private Logger logger;

    private MigrationConfiguration configuration;

    private DocumentReference rightsClassReference;

    private DocumentReference preferencesClassReference;

    /**
     * Perform the migration described on the given plan.
     *
     * @param plan the plan to execute
     * @param configuration the configuration
     * @throws MigrationException if error happens
     */
    public void performMigration(MigrationPlanTree plan, MigrationConfiguration configuration) throws MigrationException
    {
        this.configuration = configuration;
        this.rightsClassReference =
                new DocumentReference(configuration.getWikiReference().getName(), "XWiki", "XWikiGlobalRights");
        this.preferencesClassReference =
                new DocumentReference(configuration.getWikiReference().getName(), "XWiki", "XWikiPreferences");

        progressManager.pushLevelProgress(plan.getActions().size(), this);
        logger.info("Start the execution of the plan.");

        for (MigrationAction action: plan.getTopLevelAction().getChildren()) {
            performAction(action);
        }

        progressManager.popLevelProgress(this);
        logger.info("Plan have been executed.");
    }

    /**
     * Perform a migration action and all its children.
     *
     * @param action the action to perform
     */
    private void performAction(MigrationAction action)
    {
        progressManager.startStep(this);

        String sourceDocument = serializer.serialize(action.getSourceDocument());
        String targetDocument = serializer.serialize(action.getTargetDocument());
        logger.info("Converting [{}] to [{}].", sourceDocument, targetDocument);

        try {
            // Move the document (if this action is enabled by the user)
            if (!action.isIdentity() && configuration.isActionEnabled(String.format("%s_page", sourceDocument))) {
                moveDocument(action);
            }

            // Apply rights and preferences
            if (action.hasRights() || action.hasPreferences()) {
                applyRightsAndPreferences(action);
            }

        } catch (Exception e) {
            logger.warn(String.format("Failed to perform the migration of [%s].", action.getSourceDocument()), e);
        }

        // Do the children, whatever happen to the parent...
        for (MigrationAction child : action.getChildren()) {
            performAction(child);
        }
    }

    /**
     * Apply the rights and the preferences described in the given action.
     *
     * @param action the action containing the preferences and the rights to set
     *
     * @throws XWikiException if error happens
     */
    private void applyRightsAndPreferences(MigrationAction action) throws XWikiException
    {
        XWikiContext context = contextProvider.get();
        XWiki xwiki = context.getWiki();

        XWikiDocument preferencesPage = xwiki.getDocument(action.getWebPreferencesReference(), context);

        // Apply rights
        if (action.hasRights()) {
            applyRights(action, preferencesPage, context);
        }

        // Apply preferences
        if (action.hasPreferences()) {
            applyPreferences(action, preferencesPage, context);
        }

        // Save the document
        xwiki.saveDocument(preferencesPage,
                "Rights and/or preferences set by the Nested Pages Migrator Application.", context);
    }

    /**
     * Move a document according to a migration action, and update its parent field.
     *
     * @param action the action to perform
     *
     * @throws Exception if error happens
     */
    private void moveDocument(MigrationAction action) throws Exception
    {
        // Create a MoveRequest from the Refactoring API
        MoveRequest request = new MoveRequest();

        // Source, target
        request.setEntityReferences(Arrays.asList((EntityReference) action.getSourceDocument()));
        request.setDestination(action.getTargetDocument());

        // Configuration
        request.setAutoRedirect(configuration.isAddAutoRedirect());
        request.setCheckRights(false);
        request.setDeep(false);
        request.setInteractive(false);
        request.setUpdateLinks(true);

        // Job type, id
        request.setJobType(RefactoringJobs.MOVE);
        String suffix = new Date().getTime() + "-" + ThreadLocalRandom.current().nextInt(100, 1000);
        request.setId(Arrays.asList(RefactoringJobs.GROUP,
                StringUtils.removeStart(RefactoringJobs.MOVE, RefactoringJobs.GROUP_PREFIX), suffix));

        // Run the job synchronously
        jobExecutor.execute(RefactoringJobs.MOVE, request).join();

        // Update the "parent" field of the target document to point to the new parent
        EntityReference spaceParent = action.getTargetDocument().getLastSpaceReference().getParent();
        if (spaceParent.getType() == EntityType.SPACE) {
            documentAccessBridge.setDocumentParentReference(action.getTargetDocument(), new DocumentReference("WebHome",
                    new SpaceReference(spaceParent)));
        }
    }

    /**
     * Apply the preferences of the given action on the given document.
     *
     * @param action the action holding the preferences to set
     * @param document the "WebPreferences" document corresponding to the target document
     * @param context the XWiki Context
     */
    private void applyPreferences(MigrationAction action, XWikiDocument document, XWikiContext context)
    {
        String sourceDocument = serializer.serialize(action.getSourceDocument());
        BaseObject obj = document.getXObject(preferencesClassReference, true, context);
        int iter = 0;
        for (Preference preference : action.getPreferences()) {
            // Check that this preference migration is enabled by the user
            if (configuration.isActionEnabled(String.format("%s_preference_%d", sourceDocument, iter))) {
                obj.set(preference.getName(), preference.getValue(), context);
            }
            iter++;
        }
    }

    /**
     * Apply the rights of the given action on the given document.
     *
     * @param action the action holding the rights to set
     * @param document the "WebPreferences" document corresponding to the target document
     * @param context the XWiki Context
     *
     * @throws XWikiException if error happens
     */
    private void applyRights(MigrationAction action, XWikiDocument document, XWikiContext context) throws XWikiException
    {
        String sourceDocument = serializer.serialize(action.getSourceDocument());
        int iter = 0;
        // Create one XWikiGlobalRights object per right
        for (Right right : action.getRights()) {
            // Check that this right migration is enabled by the user
            if (configuration.isActionEnabled(String.format("%s_right_%d", sourceDocument, iter))) {
                BaseObject obj = document.newXObject(rightsClassReference, context);
                if (right.getUser() != null) {
                    obj.set("users", serializer.serialize(right.getUser()), context);
                }
                if (right.getGroup() != null) {
                    obj.set("groups", serializer.serialize(right.getGroup()), context);
                }
                obj.set("levels", right.getLevel(), context);
                obj.set("allow", right.isAllow() ? 1 : 0, context);
            }
            iter++;
        }
    }
}
