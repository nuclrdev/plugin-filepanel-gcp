# ☁️ Google Cloud File Panel

An official [Nuclr Commander](https://nuclr.dev) plugin that adds a **Google Cloud** root to the file panel. On load it syncs your GCP project list in the background via the `gcloud` CLI and materialises each project as a real directory, so the local file panel can navigate your cloud environment without any special GCP awareness.

## ✨ What it does

| Feature | Details |
|---|---|
| 🔄 Background sync | Fetches the project list on load and refreshes every 5 minutes |
| 📁 Virtual filesystem | Materialises GCP projects as directories under `$TMPDIR/nuclr/gcp-filepanel-v1/Google Cloud/` |
| 📄 Project info | Writes a `project-info.txt` per project with ID, name, project number, lifecycle, and fetch timestamp |
| 📋 Project list panel | Shows a scrollable list of all visible projects |
| 🔐 Authentication | Delegates entirely to `gcloud auth login` — no credentials are stored by the plugin |
| ⚠️ Error handling | Shows a dialog when `gcloud` is unavailable or returns an error; creates an explanatory file when no projects are accessible |

## ✅ Prerequisites

The `gcloud` CLI must be installed and authenticated before launching Nuclr Commander:

```bash
gcloud auth login
```

## 📁 Virtual filesystem layout

```text
$TMPDIR/nuclr/gcp-filepanel-v1/Google Cloud/
  project-alpha/
    project-info.txt
  project-beta/
    project-info.txt
```

## 📥 Installation

Copy the signed plugin archive and detached signature into the Nuclr Commander `plugins/` directory:

```text
filepanel-gcp-<version>.zip
filepanel-gcp-<version>.zip.sig
```

Nuclr Commander verifies the RSA-SHA256 signature against `nuclr-cert.pem` on load. The plugin becomes available immediately without a restart.

## ⚙️ How it works

`GcpFilePanelProvider` implements `ResourceContentPlugin` and starts a background virtual-thread refresh on `load()`. `GcloudCli` runs `gcloud projects list --format=json` and captures stdout. `GcpProjectParser` parses the JSON array into `GcpProject` records. The refresh clears and rebuilds the temp directory tree on each run. A debounce guard (`lastRefreshEpochMs`) prevents redundant refreshes within the 5-minute window.

## 🗂️ Source layout

```text
src/main/java/dev/nuclr/plugin/core/panel/gcp/
├── GcpFilePanelProvider.java     plugin entry point, sync orchestration, project list panel
├── GcloudCli.java                gcloud CLI runner
├── GcpError.java                 error types (sealed hierarchy)
├── GcpErrorDialog.java           error display dialog
├── GcpProject.java               project data record
├── GcpProjectParser.java         JSON parsing of gcloud output
└── GcpProjectRepository.java     project data access and caching
```

## 📚 Dependencies

| Library | Version | Purpose |
|---|---|---|
| `dev.nuclr:platform-sdk` | `2.0.1` | Nuclr platform interfaces |
| `jackson-databind` | `2.21.1` | JSON parsing of gcloud output |

## 📜 License

Apache License 2.0 — see [LICENSE](LICENSE).
