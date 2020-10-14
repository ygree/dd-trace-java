package datadog.trace.bootstrap.instrumentation.api.ci;

class TravisInfo extends CIProviderInfo {

  // https://docs.travis-ci.com/user/environment-variables/#default-environment-variables
  public static final String TRAVIS = "TRAVIS";
  public static final String TRAVIS_PROVIDER_NAME = "travisci";
  public static final String TRAVIS_PIPELINE_ID = "TRAVIS_BUILD_ID";
  public static final String TRAVIS_PIPELINE_NUMBER = "TRAVIS_BUILD_NUMBER";
  public static final String TRAVIS_PIPELINE_URL = "TRAVIS_BUILD_WEB_URL";
  public static final String TRAVIS_JOB_URL = "TRAVIS_JOB_WEB_URL";
  public static final String TRAVIS_WORKSPACE_PATH = "TRAVIS_BUILD_DIR";
  public static final String TRAVIS_REPOSITORY_SLUG = "TRAVIS_REPO_SLUG";
  public static final String TRAVIS_PR_REPOSITORY_SLUG = "TRAVIS_PULL_REQUEST_SLUG";
  public static final String TRAVIS_GIT_COMMIT = "TRAVIS_COMMIT";
  public static final String TRAVIS_GIT_PR_BRANCH = "TRAVIS_PULL_REQUEST_BRANCH";
  public static final String TRAVIS_GIT_BRANCH = "TRAVIS_BRANCH";
  public static final String TRAVIS_GIT_TAG = "TRAVIS_TAG";

  private final String ciProviderName;
  private final String ciPipelineId;
  private final String ciPipelineName;
  private final String ciPipelineNumber;
  private final String ciPipelineUrl;
  private final String ciJobUrl;
  private final String ciWorkspacePath;
  private final String gitRepositoryUrl;
  private final String gitCommit;
  private final String gitBranch;
  private final String gitTag;

  TravisInfo() {
    ciProviderName = TRAVIS_PROVIDER_NAME;
    ciPipelineId = System.getenv(TRAVIS_PIPELINE_ID);
    ciPipelineNumber = System.getenv(TRAVIS_PIPELINE_NUMBER);
    ciPipelineUrl = System.getenv(TRAVIS_PIPELINE_URL);
    ciJobUrl = System.getenv(TRAVIS_JOB_URL);
    ciWorkspacePath = expandTilde(System.getenv(TRAVIS_WORKSPACE_PATH));
    ciPipelineName = buildCiPipelineName();
    gitRepositoryUrl = buildGitRepositoryUrl();
    gitCommit = System.getenv(TRAVIS_GIT_COMMIT);
    gitTag = normalizeRef(System.getenv(TRAVIS_GIT_TAG));
    gitBranch = buildGitBranch(gitTag);
  }

  private String buildGitBranch(final String gitTag) {
    if (gitTag != null) {
      return null;
    }

    final String fromBranch = System.getenv(TRAVIS_GIT_PR_BRANCH);
    if (fromBranch != null && !fromBranch.isEmpty()) {
      return normalizeRef(fromBranch);
    } else {
      return normalizeRef(System.getenv(TRAVIS_GIT_BRANCH));
    }
  }

  private String buildGitRepositoryUrl() {
    String repoSlug = System.getenv(TRAVIS_PR_REPOSITORY_SLUG);
    if (repoSlug == null || repoSlug.isEmpty()) {
      repoSlug = System.getenv(TRAVIS_REPOSITORY_SLUG);
    }
    return String.format("https://github.com/%s.git", repoSlug);
  }

  private String buildCiPipelineName() {
    String repoSlug = System.getenv(TRAVIS_PR_REPOSITORY_SLUG);
    if (repoSlug == null || repoSlug.isEmpty()) {
      repoSlug = System.getenv(TRAVIS_REPOSITORY_SLUG);
    }
    return repoSlug;
  }

  @Override
  public String getCiProviderName() {
    return ciProviderName;
  }

  @Override
  public String getCiPipelineId() {
    return ciPipelineId;
  }

  @Override
  public String getCiPipelineName() {
    return ciPipelineName;
  }

  @Override
  public String getCiPipelineNumber() {
    return ciPipelineNumber;
  }

  @Override
  public String getCiPipelineUrl() {
    return ciPipelineUrl;
  }

  @Override
  public String getCiJobUrl() {
    return ciJobUrl;
  }

  @Override
  public String getCiWorkspacePath() {
    return ciWorkspacePath;
  }

  @Override
  public String getGitRepositoryUrl() {
    return gitRepositoryUrl;
  }

  @Override
  public String getGitCommit() {
    return gitCommit;
  }

  @Override
  public String getGitBranch() {
    return gitBranch;
  }

  @Override
  public String getGitTag() {
    return gitTag;
  }
}
