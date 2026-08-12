# ☁️ Google Cloud File Panel

An official [Nuclr Commander](https://nuclr.dev) plugin that adds a `GCP`
drive entry to the file panel. 🚀 It uses the local `gcloud` CLI to show Google
Cloud projects and selected project resources as virtual panel entries.

🔒 The plugin does not store credentials. Authentication and authorization are
delegated to the active `gcloud` account.

## ✨ What It Does

| Feature | Details |
|---|---|
| 🗂️ Project browser | Lists projects visible to the current `gcloud` account |
| 🧩 Service browser | Shows Cloud Storage, Pub/Sub, Compute Engine, GKE, and Secret Manager under each project |
| 🪣 Cloud Storage | Lists buckets, folders, and objects with paged loading (a trailing `Load more…` row) for large prefixes |
| 📥 GCS copy | Copies GCS objects to a local folder and accepts incoming file copies as uploads, with conflict and progress dialogs |
| 🛠️ GCS actions | Make folder (F7), delete (F8), find by name (Alt+F7), copy (F5), quick view, and Console object pages |
| 📨 Pub/Sub | Lists topics and subscriptions and opens their Console detail pages |
| 🔑 Secret Manager | Lists secrets and opens their Console versions pages |
| 🖥️ Compute Engine | Section shortcuts: VMs, storage, instance groups, extension/VM manager, Bare Metal Solution, settings |
| ☸️ GKE | Lists clusters and workloads via `kubectl`, plus Console shortcuts for AI/ML, Teams, Backup for GKE and the rest of Resources management |
| ⚡ Streaming listings | Entries are published to the panel as they arrive, so large listings paint incrementally |
| 🌐 Console shortcuts | Opens Console pages for resource manager and create project/bucket/secret/topic |
| 💽 Disk cache | Persists project, bucket, cluster, workload, and complete object listings under the temp directory |

## 📋 Prerequisites

Install and authenticate the Google Cloud CLI before launching Nuclr Commander:

```bash
gcloud auth login
```

The active account must have permission to list the resources you want to
browse. Browsing GKE workloads additionally requires `kubectl` on the `PATH`
(the plugin calls it against the cluster's credentials).

## 🧭 Navigation

```text
GCP
  <project-id>
    GCS
      <bucket>
        <folder-or-object>
    Pub/Sub
      Topics
      Subscriptions
    Compute Engine
      VMs · Storage · Instance groups · VM manager · …
    GKE
      Resources management
        Clusters
          <cluster>
        Workloads
          <workload>
      Features
    Secret Manager
      <secret>
```

Common function keys are exposed only where they apply. For example, GCS object
folders expose copy, make-folder, delete, and find actions, while Pub/Sub topics
expose create-topic.

## 💾 Caching

Listings are served from memory first, then from a restart-persistent disk cache
under:

```text
<java.io.tmpdir>/nuclr-gcp-cache/
```

The cache does not expire on a timer. Use the panel refresh action to invalidate
the currently open listing and re-query `gcloud`.

## 🔨 Build

```bash
mvn -q test
mvn -q package -DskipTests
```

`package` creates the plugin ZIP in `target/`. The `verify` phase also creates a
detached ZIP signature and requires the configured signing keystore and
`jarsigner.storepass` property.

## 📂 Source Layout

```text
src/main/java/dev/nuclr/plugin/core/panel/gcp/
  GcpFilePanelProvider.java       plugin entry point and panel orchestration
  GcpResource.java                virtual GCP resource model and Console URLs
  GcloudCli.java                  gcloud executable and command helpers
  GcpDiskCache.java               restart-persistent listing cache
  GcpError*.java                  error classification and dialogs
  GcpProject*.java                project model, gcloud output parsing, repository
  gcs/                            Cloud Storage listing, paging, copy, upload, delete, find
  pubsub/                         Pub/Sub topic/subscription listing
  gke/                            GKE clusters and workloads (gcloud + kubectl)
  secret/                         Secret Manager listing
```

## 📦 Dependencies

| Library | Version | Purpose |
|---|---|---|
| `dev.nuclr:platform-sdk` | `3.0.2` | Nuclr platform interfaces |
| `org.projectlombok:lombok` | `1.18.42` | Generated logging boilerplate |
| `org.slf4j:slf4j-api` | `2.0.17` | Logging API |
| `com.fasterxml.jackson.core:jackson-databind` | `2.21.1` | JSON parsing of `gcloud` output |

## 📄 License

Apache License 2.0. See [LICENSE](LICENSE). ⚖️
