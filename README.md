# update-compose-multiplatform

A reusable workflow that updates Compose Multiplatform and its related libraries in the right combination.

## Table of contents

- [Why it was built](#why-it-was-built)
- [What it does](#what-it-does)
- [How to use](#how-to-use)
- [License](#license)

## Why it was built

I was maintaining my Compose Multiplatform apps with Dependabot, and learned that simply bumping each library to its latest version does not work: the versions of the libraries around Compose Multiplatform have to be chosen to match Compose Multiplatform itself. Matching them by hand on every update was tedious, so I built a workflow just for that.

## What it does

It reads the [CHANGELOG](https://github.com/JetBrains/compose-multiplatform/blob/master/CHANGELOG.md) in the Compose Multiplatform repository on GitHub, updates the version catalog to match the latest stable release listed there, and opens a pull request. Optionally, it can also update yarn.lock and enable auto-merge.

## How to use

### Prerequisites

- A GitHub App that has read and write access to the contents and the pull requests of your repository, and is installed on your repository. The workflow opens the pull request as this app.
- The client ID of the app, saved as a variable in your repository.
- A private key of the app, saved as a secret in your repository.

If you already have such an app, for example to enable auto-merge on Dependabot pull requests, you can use it here too. To set these up, follow [Authenticating with a GitHub App](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/making-authenticated-api-requests-with-a-github-app-in-a-github-actions-workflow#authenticating-with-a-github-app) in the GitHub documentation.

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

- `<version>` with the version of this workflow to use, such as `v1`.
- `<PULL_REQUEST_APP_CLIENT_ID>` and `<PULL_REQUEST_APP_PRIVATE_KEY>` with the names of your variable and your secret.

The file runs the workflow every day at 7:31 UTC. To run it at another time, see how to write the schedule in [the GitHub Actions documentation](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#schedule). Each run opens a pull request when an update is available.

### Options

To update yarn.lock or enable auto-merge as well, add inputs under `with` in the file. With every option turned on, that part of the file becomes:

```yaml
with:
  update-js-yarn-lock: true
  update-wasm-yarn-lock: true
  jdk-distribution-to-update-yarn-lock: zulu
  jdk-version-to-update-yarn-lock: "21"
  app-client-id: ${{ vars.<PULL_REQUEST_APP_CLIENT_ID> }}
  auto-merge: squash
  auto-merge-update-types: minor,patch
```

### Inputs

| Name                                   | Default   | Description                                                                                                                                                       |
| -------------------------------------- | --------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `update-js-yarn-lock`                  | `false`   | Set to `true` to update `kotlin-js-store/yarn.lock` with `./gradlew kotlinUpgradeYarnLock`.                                                                       |
| `update-wasm-yarn-lock`                | `false`   | Set to `true` to update `kotlin-js-store/wasm/yarn.lock` with `./gradlew kotlinWasmUpgradeYarnLock`.                                                              |
| `jdk-distribution-to-update-yarn-lock` |           | The JDK distribution to run Gradle on, as [actions/setup-java](https://github.com/actions/setup-java) names it. Required when yarn.lock is updated.               |
| `jdk-version-to-update-yarn-lock`      |           | The JDK version to run Gradle on. Required when yarn.lock is updated.                                                                                             |
| `app-client-id`                        |           | The client ID of the GitHub App that opens the pull request. Pass it together with the `app-private-key` secret.                                                  |
| `auto-merge`                           | `disable` | The merge method to enable auto-merge with: `squash`, `merge` or `rebase`. `disable` leaves auto-merge off.                                                       |
| `auto-merge-update-types`              |           | The update types of Compose Multiplatform to enable auto-merge for, separated by commas: `major`, `minor` and `patch`. Required unless `auto-merge` is `disable`. |

### Secrets

| Name              | Description                                                                                                     |
| ----------------- | --------------------------------------------------------------------------------------------------------------- |
| `app-private-key` | The private key of the GitHub App that opens the pull request. Pass it together with the `app-client-id` input. |

## License

[MIT](LICENSE)
