# update-compose-multiplatform

A reusable workflow that keeps the version catalog of a Compose Multiplatform project up to date, together with the libraries that have to match Compose Multiplatform and the JS and Wasm lock files.

## Table of contents

- [Why it was built](#why-it-was-built)
- [What it does](#what-it-does)
- [How to use](#how-to-use)
- [License](#license)

## Why it was built

I was maintaining my Compose Multiplatform apps with Dependabot and Renovate, and learned that they do not fit such a project. The libraries around Compose Multiplatform have to be chosen to match Compose Multiplatform itself, not bumped to their latest versions. A new Kotlin breaks the build until the JS lock file is updated, and a new Compose Multiplatform does the same to the Wasm lock file when the project uses npm. Leaving only some libraries to this workflow still left the lock files broken by the others, so it now takes care of the whole version catalog.

## What it does

On each run, it reads `gradle/libs.versions.toml` of your default branch and:

- Moves Compose Multiplatform to the latest stable release in [its CHANGELOG](https://github.com/JetBrains/compose-multiplatform/blob/master/CHANGELOG.md), and the libraries that the CHANGELOG pairs with it to the versions listed for that release. They are not updated while Compose Multiplatform stays where it is.
- Moves every other library and plugin to its latest version in Maven Central, the Google Maven repository or the Gradle Plugin Portal.
- Keeps a stable version on stable versions. A prerelease moves to a later prerelease of the same version, or to a stable version.
- Updates the lock files that the project has, whichever dependency was updated: `kotlin-js-store/yarn.lock`, `kotlin-js-store/wasm/yarn.lock`, `kotlin-js-store/package-lock.json` and `kotlin-js-store/wasm/package-lock.json`.
- Opens a pull request with the changes, or updates the one it opened before, and closes it when it is no longer needed. With auto-merge turned on, the updates that may merge automatically go to a second pull request that has auto-merge enabled.

Versions written as ranges, such as `3.+`, rich versions, such as `{ strictly = "1.0" }`, and libraries without a version are left as they are.

## How to use

### Prerequisites

- A GitHub App that has read and write access to the contents and the pull requests of your repository, and is installed on your repository. The workflow opens the pull requests as this app.
- The client ID of the app, saved as a variable in your repository.
- A private key of the app, saved as a secret in your repository.

If you already have such an app, for example to enable auto-merge on Renovate pull requests, you can use it here too. To set these up, follow [Authenticating with a GitHub App](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/making-authenticated-api-requests-with-a-github-app-in-a-github-actions-workflow#authenticating-with-a-github-app) in the GitHub documentation.

The workflow runs `./gradlew` of your project to update the lock files in a job that can only read your repository. The job that holds the token of the app runs none of your build, and takes nothing from the first job but the version catalog and the lock files.

Stop Dependabot or Renovate from updating the version catalog, so that they do not open pull requests that compete with this workflow.

### Installation

Add the following file to your repository as `.github/workflows/update-compose-multiplatform.yml`:

```yaml
name: Update Compose Multiplatform
on:
  schedule:
    - cron: "31 7 * * *"
  workflow_dispatch:
permissions: {}
jobs:
  update-compose-multiplatform:
    permissions:
      contents: read
    uses: yuyuyuyuyu-dev/update-compose-multiplatform/.github/workflows/update-compose-multiplatform.yml@<version>
    with:
      app-client-id: ${{ vars.<PULL_REQUEST_APP_CLIENT_ID> }}
    secrets:
      app-private-key: ${{ secrets.<PULL_REQUEST_APP_PRIVATE_KEY> }}
```

In the file, replace:

- `<version>` with the version of this workflow to use, such as `v2`.
- `<PULL_REQUEST_APP_CLIENT_ID>` and `<PULL_REQUEST_APP_PRIVATE_KEY>` with the names of your variable and your secret.

The `contents: read` permission lets the job that updates the lock files check out your repository.

The file runs the workflow every day at 7:31 UTC. To run it at another time, see how to write the schedule in [the GitHub Actions documentation](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#schedule).

### Options

To enable auto-merge, update a project that is not at the root of your repository, or run Gradle on another JDK, add inputs under `with` in the file. With every option turned on, that part of the file becomes:

```yaml
with:
  app-client-id: ${{ vars.<PULL_REQUEST_APP_CLIENT_ID> }}
  auto-merge: squash
  auto-merge-update-types: minor,patch
  project-directory-to-update: example
  jdk-distribution: temurin
  jdk-version: "17"
```

### Inputs

| Name                          | Default   | Description                                                                                                                                                                   |
| ----------------------------- | --------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `app-client-id`               |           | The client ID of the GitHub App that opens the pull requests. Pass it together with the `app-private-key` secret.                                                             |
| `auto-merge`                  | `disable` | The merge method to enable auto-merge with: `squash`, `merge` or `rebase`. `disable` leaves auto-merge off and puts every update in one pull request.                         |
| `auto-merge-update-types`     |           | The update types to enable auto-merge for, separated by commas: `major`, `minor` and `patch`. Required unless `auto-merge` is `disable`.                                      |
| `project-directory-to-update` | `.`       | The directory of the Compose Multiplatform project to keep up to date, relative to the root of your repository. Set it when the project is not at the root.                   |
| `jdk-distribution`            | `zulu`    | The JDK distribution to run Gradle on, as [actions/setup-java](https://github.com/actions/setup-java) names it. The default matches the Compose Multiplatform project wizard. |
| `jdk-version`                 | `21`      | The JDK version to run Gradle on.                                                                                                                                             |

The update type of an update comes from the first three numbers of the version. Compose Multiplatform and the libraries paired with it move together, so they take the update type of Compose Multiplatform.

### Secrets

| Name              | Description                                                                                                      |
| ----------------- | ---------------------------------------------------------------------------------------------------------------- |
| `app-private-key` | The private key of the GitHub App that opens the pull requests. Pass it together with the `app-client-id` input. |

## License

[MIT](LICENSE)
