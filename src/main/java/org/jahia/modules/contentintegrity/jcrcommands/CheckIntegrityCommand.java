package org.jahia.modules.contentintegrity.jcrcommands;

import org.apache.commons.lang.StringUtils;
import org.apache.karaf.shell.api.action.Action;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.Option;
import org.apache.karaf.shell.api.action.lifecycle.Reference;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.apache.karaf.shell.api.console.Session;
import org.jahia.modules.contentintegrity.services.ContentIntegrityResults;
import org.jahia.modules.contentintegrity.services.Utils;

@Command(scope = "jcr", name = "integrity-check", description = "Runs an integrity scan")
@Service
public class CheckIntegrityCommand extends JCRCommandSupport implements Action {

    @Reference
    Session session;

    @Option(name = "-l", aliases = "--limit", description = "Maximum number of lines to print")
    private String limit;

    @Override
    public Object execute() throws Exception {
        final String currentPath = StringUtils.defaultString(getCurrentPath(session), "/");
        final ContentIntegrityResults integrityResults = Utils.getContentIntegrityService().validateIntegrity(currentPath, getCurrentWorkspace(session));
        printContentIntegrityErrors(integrityResults, limit);
        return null;
    }

}
