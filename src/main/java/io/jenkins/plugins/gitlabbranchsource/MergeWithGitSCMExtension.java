package io.jenkins.plugins.gitlabbranchsource;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.plugins.git.GitException;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.Revision;
import hudson.plugins.git.extensions.GitSCMExtension;
import hudson.plugins.git.extensions.impl.PreBuildMerge;
import hudson.plugins.git.util.MergeRecord;
import java.io.IOException;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.jenkinsci.plugins.gitclient.CheckoutCommand;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.jenkinsci.plugins.gitclient.MergeCommand;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Similar to {@link PreBuildMerge}, but we cannot use that unmodified: we need to specify the exact
 * base branch hash. The hash is specified so that we are not subject to a race condition between
 * the {@code baseHash} we think we are merging with and a possibly newer one that was just pushed.
 */
@Restricted(NoExternalUse.class)
public class MergeWithGitSCMExtension extends GitSCMExtension {

    @NonNull
    private final String baseName;
    @CheckForNull
    private final String baseHash;

    MergeWithGitSCMExtension(@NonNull String baseName, @CheckForNull String baseHash) {
        this.baseName = baseName;
        this.baseHash = baseHash;
    }

    @NonNull
    public String getBaseName() {
        return baseName;
    }

    public String getBaseHash() {
        return baseHash;
    }

    @Override
    public Revision decorateRevisionToBuild(GitSCM scm, Run<?, ?> build, GitClient git,
        TaskListener listener,
        Revision marked, Revision rev)
        throws IOException, InterruptedException, GitException {
        ObjectId baseObjectId;
        if (StringUtils.isBlank(baseHash)) {
            try {
                baseObjectId = git.revParse(Constants.R_REFS + baseName);
            } catch (GitException e) {
                listener.getLogger()
                    .printf("Unable to determine head revision of %s prior to merge with PR%n",
                        baseName);
                throw e;
            }
        } else {
            baseObjectId = ObjectId.fromString(baseHash);
        }
        listener.getLogger().printf("Merging %s commit %s into PR head commit %s%n",
            baseName, baseObjectId.name(), rev.getSha1String()
        );
        checkout(scm, build, git, listener, rev);
        try {
            /* could parse out of JenkinsLocationConfiguration.get().getAdminAddress() but seems overkill */
            git.setAuthor("Jenkins", "nobody@nowhere");
            git.setCommitter("Jenkins", "nobody@nowhere");
            MergeCommand cmd = git.merge().setRevisionToMerge(baseObjectId);
            for (GitSCMExtension ext : scm.getExtensions()) {
                // By default we do a regular merge, allowing it to fast-forward.
                ext.decorateMergeCommand(scm, build, git, listener, cmd);
            }
            cmd.execute();
        } catch (GitException x) {
            // Try to revert merge conflict markers.
            // TODO IGitAPI offers a reset(hard) method yet GitClient does not. Why?
            checkout(scm, build, git, listener, rev);
            // TODO would be nicer to throw an AbortException with just the message, but this is actually worse
            // until git-client 1.19.7+
            throw x;
        }
        build.addAction(
            new MergeRecord(baseName,
                baseObjectId.getName())); // does not seem to be used, but just in case
        ObjectId mergeRev = git.revParse(Constants.HEAD);
        listener.getLogger().println("Merge succeeded, producing " + mergeRev.name());
        return new Revision(mergeRev,
            rev.getBranches()); // note that this ensures Build.revision != Build.marked
    }

    private void checkout(GitSCM scm, Run<?, ?> build, GitClient git, TaskListener listener,
        Revision rev)
        throws InterruptedException, IOException, GitException {
        CheckoutCommand checkoutCommand = git.checkout().ref(rev.getSha1String());
        for (GitSCMExtension ext : scm.getExtensions()) {
            ext.decorateCheckoutCommand(scm, build, git, listener, checkoutCommand);
        }
        checkoutCommand.execute();
    }

}
