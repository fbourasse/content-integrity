package org.jahia.modules.contentintegrity.services.checks;

import org.jahia.api.Constants;
import org.jahia.modules.contentintegrity.api.ContentIntegrityCheck;
import org.jahia.modules.contentintegrity.services.ContentIntegrityError;
import org.jahia.modules.contentintegrity.services.ContentIntegrityErrorList;
import org.jahia.modules.contentintegrity.services.impl.AbstractContentIntegrityCheck;
import org.jahia.services.content.JCRNodeIteratorWrapper;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.ItemNotFoundException;
import javax.jcr.RepositoryException;

import static org.jahia.api.Constants.JAHIANT_PAGE;

@Component(service = ContentIntegrityCheck.class, immediate = true, property = {
        ContentIntegrityCheck.ExecutionCondition.APPLY_ON_NT + "=" + Constants.JAHIANT_VIRTUALSITE,
        ContentIntegrityCheck.ExecutionCondition.APPLY_ON_SUBTREES + "=" + "/sites"
})
public class HomePageDeclaration extends AbstractContentIntegrityCheck implements AbstractContentIntegrityCheck.SupportsIntegrityErrorFix {

    private static final Logger logger = LoggerFactory.getLogger(HomePageDeclaration.class);
    private static final String HOME_PAGE_FLAG = "j:isHomePage";
    private static final String HOME_PAGE_FALLBACK_NAME = "home";

    private enum ErrorType {NO_HOME, MULTIPLE_HOMES, FALLBACK_ON_NAME}

    @Override
    public ContentIntegrityErrorList checkIntegrityBeforeChildren(JCRNodeWrapper node) {
        try {
            int flaggedAsHomeCount = 0;
            final JCRNodeIteratorWrapper iterator = node.getNodes();
            while (iterator.hasNext()) {
                final JCRNodeWrapper child = (JCRNodeWrapper) iterator.nextNode();
                if (child.isNodeType(JAHIANT_PAGE) && child.hasProperty(HOME_PAGE_FLAG) && child.getProperty(HOME_PAGE_FLAG).getBoolean())
                    flaggedAsHomeCount++;
            }
            if (flaggedAsHomeCount != 1) {
                final String msg;
                final ErrorType errortype;
                if (flaggedAsHomeCount > 1) {
                    errortype = ErrorType.MULTIPLE_HOMES;
                    msg = String.format("The site %s has several pages flagged as home", node.getName());
                } else if (node.hasNode(HOME_PAGE_FALLBACK_NAME)) {
                    if (isInLiveWorkspace(node))
                        return null; // Let's consider it is not an error, if fixed in default, then it will get fixed in live after publishing
                    errortype = ErrorType.FALLBACK_ON_NAME;
                    msg = String.format("The site %s has no page flagged as home, but one is named 'home'", node.getName());
                } else {
                    if (isInLiveWorkspace(node))
                        return null; // Not an error, the home page has maybe not yet been published
                    errortype = ErrorType.NO_HOME;
                    msg = String.format("The site %s has no page flagged as home and no one is named 'home'", node.getName());
                }

                final ContentIntegrityError error = createError(node, msg);
                error.setExtraInfos(errortype);
                return createSingleError(error);
            }
        } catch (RepositoryException e) {
            logger.error("", e);
        }
        return null;
    }

    @Override
    public boolean fixError(JCRNodeWrapper site, ContentIntegrityError integrityError) throws RepositoryException {
        final Object errorExtraInfos = integrityError.getExtraInfos();
        if (!(errorExtraInfos instanceof ErrorType)) {
            logger.error("Unexpected error type: " + errorExtraInfos);
            return false;
        }
        final ErrorType errorType = (ErrorType) errorExtraInfos;
        final JCRNodeIteratorWrapper iterator;
        switch (errorType) {
            case NO_HOME:
                iterator = site.getNodes();
                while (iterator.hasNext()) {
                    final JCRNodeWrapper child = (JCRNodeWrapper) iterator.nextNode();
                    if (child.isNodeType(JAHIANT_PAGE)) {
                        child.setProperty(HOME_PAGE_FLAG, true);
                        child.getSession().save();
                        return true;
                    }
                }
                return false;
            case MULTIPLE_HOMES:
                if (isInDefaultWorkspace(site)) {
                    JCRNodeWrapper home = null;
                    if (site.hasNode(HOME_PAGE_FALLBACK_NAME)) {
                        home = site.getNode(HOME_PAGE_FALLBACK_NAME);
                        if (!(home.hasProperty(HOME_PAGE_FLAG) && home.getProperty(HOME_PAGE_FLAG).getBoolean())) {
                            home = null; // means it was actually not this one
                        }
                    }
                    iterator = site.getNodes();
                    while (iterator.hasNext()) {
                        final JCRNodeWrapper child = (JCRNodeWrapper) iterator.nextNode();
                        if (child.isNodeType(JAHIANT_PAGE) && child.hasProperty(HOME_PAGE_FLAG) && child.getProperty(HOME_PAGE_FLAG).getBoolean()) {
                            if (home == null) home = child;
                            else
                                child.getProperty(HOME_PAGE_FLAG).remove();
                        }
                    }
                    return true;
                } else {
                    final JCRSessionWrapper session_default = JCRSessionFactory.getInstance().getCurrentSystemSession(Constants.EDIT_WORKSPACE, null, null);
                    JCRNodeWrapper home_default = null;
                    final JCRNodeWrapper site_default = session_default.getNode(site.getPath());
                    for (JCRNodeWrapper child : site_default.getNodes()) {
                        if (child.isNodeType(JAHIANT_PAGE) && child.hasProperty(HOME_PAGE_FLAG) && child.getProperty(HOME_PAGE_FLAG).getBoolean()) {
                            if (home_default == null) home_default = child;
                            else {
                                logger.error("Impossible to fix the error in live. It needs to be fixed in default first."); // multiple home in default
                                return false;
                            }
                        }
                    }
                    if (home_default == null) {
                        logger.error("Impossible to fix the error in live. It needs to be fixed in default first.");
                        return false; // no home in default
                    }
                    JCRNodeWrapper home = null;
                    try {
                        home = site.getSession().getNodeByIdentifier(home_default.getIdentifier());
                    } catch (ItemNotFoundException infe) {
                        // home not yet published
                    }
                    iterator = site.getNodes();
                    while (iterator.hasNext()) {
                        final JCRNodeWrapper child = (JCRNodeWrapper) iterator.nextNode();
                        if (child.isNodeType(JAHIANT_PAGE) && child.hasProperty(HOME_PAGE_FLAG) && child.getProperty(HOME_PAGE_FLAG).getBoolean()) {
                            if (home != null && home.getIdentifier().equals(child.getIdentifier())) continue;
                            child.getProperty(HOME_PAGE_FLAG).remove();
                        }
                    }
                    site.getSession().save();
                    return true;
                }
            case FALLBACK_ON_NAME:
                final JCRNodeWrapper home = site.getNode(HOME_PAGE_FALLBACK_NAME);
                home.setProperty(HOME_PAGE_FLAG, true);
                home.getSession().save();
                return true;
        }
        return false;
    }
}
