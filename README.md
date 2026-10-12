# update-compose-multiplatform

A reusable workflow that updates Compose Multiplatform and its related libraries in the right combination.

## Table of contents

- [Why it was built](#why-it-was-built)
- [What it does](#what-it-does)
- [How to use](#how-to-use)
- [License](#license)

## Why it was built

I was maintaining my Compose Multiplatform apps with Dependabot, and one day the tests started to fail. I asked AI to handle it in each repository, and the sessions split evenly in two: half followed my instructions as given, and half raised concerns about them. Rather than work through the disagreement in every repository myself, I let the sessions discuss it with each other, and they concluded that, since JetBrains publishes a table of the versions that go together, the versions should follow that table. Following the official guidance seemed reasonable to me. But doing so separately in each repository seemed wasteful, so I had them build a reusable workflow in a repository of its own.

## What it does

It reads the [CHANGELOG](https://github.com/JetBrains/compose-multiplatform/blob/master/CHANGELOG.md) in the Compose Multiplatform repository on GitHub, updates the version catalog to match the latest stable release listed there, and opens a pull request. Optionally, it can also update yarn.lock and enable auto-merge.

## How to use

Call the workflow from a workflow in your repository, and give it either a token or a GitHub App to open the pull request with. In the workflows below, replace `<version>` with the version to use, such as `v1`.

With a personal access token:

```yaml
name: Update Compose Multiplatform
on:
  schedule:
    - cron: "0 0 * * *"
  workflow_dispatch:
permissions: {}
jobs:
  update-compose-multiplatform:
    permissions:
      contents: read
    uses: yuyuyuyuyu-dev/update-compose-multiplatform/.github/workflows/update-compose-multiplatform.yml@<version>
    secrets:
      token: ${{ secrets.UPDATE_COMPOSE_MULTIPLATFORM_TOKEN }}
```

With a GitHub App, and with every option turned on:

```yaml
name: Update Compose Multiplatform
on:
  schedule:
    - cron: "0 0 * * *"
  workflow_dispatch:
permissions: {}
jobs:
  update-compose-multiplatform:
    permissions:
      contents: read
    uses: yuyuyuyuyu-dev/update-compose-multiplatform/.github/workflows/update-compose-multiplatform.yml@<version>
    with:
      update-js-yarn-lock: true
      update-wasm-yarn-lock: true
      jdk-distribution-to-update-yarn-lock: temurin
      jdk-version-to-update-yarn-lock: "21"
      app-client-id: ${{ vars.UPDATE_COMPOSE_MULTIPLATFORM_APP_CLIENT_ID }}
      auto-merge: squash
      auto-merge-update-types: minor,patch
      labels: dependencies
    secrets:
      app-private-key: ${{ secrets.UPDATE_COMPOSE_MULTIPLATFORM_APP_PRIVATE_KEY }}
```

The token or the app needs read and write access to the contents and the pull requests of your repository. The `GITHUB_TOKEN` cannot be used, because pull requests opened with it do not run workflows.

### Inputs

| Name | Default | Description |
| --- | --- | --- |
| `update-js-yarn-lock` | `false` | Set to `true` to update `kotlin-js-store/yarn.lock` with `./gradlew kotlinUpgradeYarnLock`. |
| `update-wasm-yarn-lock` | `false` | Set to `true` to update `kotlin-js-store/wasm/yarn.lock` with `./gradlew kotlinWasmUpgradeYarnLock`. |
| `jdk-distribution-to-update-yarn-lock` | | The JDK distribution to run Gradle on, as [actions/setup-java](https://github.com/actions/setup-java) names it. Required when yarn.lock is updated. |
| `jdk-version-to-update-yarn-lock` | | The JDK version to run Gradle on. Required when yarn.lock is updated. |
| `app-client-id` | | The client ID of the GitHub App that opens the pull request. Pass it together with the `app-private-key` secret, instead of the `token` secret. |
| `auto-merge` | `disable` | The merge method to enable auto-merge with: `squash`, `merge` or `rebase`. `disable` leaves auto-merge off. |
| `auto-merge-update-types` | | The update types of Compose Multiplatform to enable auto-merge for, separated by commas: `major`, `minor` and `patch`. Required unless `auto-merge` is `disable`. |
| `labels` | | The labels to add to the pull request when it is opened, separated by commas. |

### Secrets

| Name | Description |
| --- | --- |
| `token` | A token that belongs to a user, such as a personal access token. Pass it instead of `app-client-id` and `app-private-key`. |
| `app-private-key` | The private key of the GitHub App that opens the pull request. Pass it together with the `app-client-id` input. |

## License

[MIT](LICENSE)
