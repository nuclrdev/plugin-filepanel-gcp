package dev.nuclr.plugin.core.panel.gcp;

import java.io.IOException;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.nuclr.platform.plugin.FilePanelNuclrPlugin;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import lombok.extern.slf4j.Slf4j;

/**
 * GCP file panel plugin (platform-sdk 3.x).
 *
 * <p>Contributes a single <b>GCP</b> entry to the Alt+F1/Alt+F2 drive selector. Opening
 * it lists the GCP projects the current user can access, queried live via the
 * {@code gcloud} CLI ({@code gcloud projects list}). Each project is shown as a navigable
 * folder; entering one shows a {@code ..} back to the project list (per-project contents
 * are out of scope for now).
 *
 * <p>The panel is built from purely virtual {@link GcpResource}s (no local path), so the
 * local filesystem plugin never claims them and the host routes navigation here via
 * {@link #supports(NuclrResource)}. Authentication is the user's responsibility
 * ({@code gcloud auth login}); this plugin only reads what gcloud already exposes.
 */
@Slf4j
public class GcpFilePanelProvider implements FilePanelNuclrPlugin {

	public static final String PluginId = "dev.nuclr.plugin.core.panel.gcp";

	private static final String PluginName = "Google Cloud Platform Panel";
	private static final String PluginVersion = loadVersion();
	private static final String PluginDescription =
			"Lists GCP projects accessible to the current gcloud account as a navigable panel.";
	private static final String PluginAuthor = "Nuclr Development Team";
	private static final String PluginLicense = "Apache-2.0";
	private static final String PluginWebsite = "https://nuclr.dev";
	private static final String PluginPageUrl = "https://nuclr.dev/plugins/core/filepanel-gcp.html";

	/** Columns shown for the project listing (cells read from each resource's metadata). */
	private static final List<String> COLUMNS = List.of("Name", "Project Name", "Number", "State");

	/** How long a successful project list stays valid before {@code gcloud} is re-run. */
	private static final long CACHE_TTL_MS = 5 * 60_000L;

	private final String uuid = UUID.randomUUID().toString();
	private final GcpProjectRepository repository = new GcpProjectRepository();

	private NuclrPluginContext context;
	private boolean focused;
	private NuclrResource currentResource;

	// Short-lived cache so re-entering the root (e.g. ".." back from a project) does not
	// re-run gcloud on every navigation. Written from the background read thread.
	private volatile List<GcpProject> cachedProjects;
	private volatile long cachedAtMs;

	// -------------------------------------------------------------------------
	// Plugin metadata
	// -------------------------------------------------------------------------

	@Override
	public String id() {
		return PluginId;
	}

	@Override
	public String name() {
		return PluginName;
	}

	@Override
	public String version() {
		return PluginVersion;
	}

	@Override
	public String description() {
		return PluginDescription;
	}

	@Override
	public String author() {
		return PluginAuthor;
	}

	@Override
	public String license() {
		return PluginLicense;
	}

	@Override
	public String website() {
		return PluginWebsite;
	}

	@Override
	public String pageUrl() {
		return PluginPageUrl;
	}

	@Override
	public String docUrl() {
		return PluginPageUrl;
	}

	@Override
	public Developer developer() {
		return Developer.Official;
	}

	@Override
	public String uuid() {
		return uuid;
	}

	private static String loadVersion() {
		try (var stream = GcpFilePanelProvider.class.getResourceAsStream("/plugin.properties")) {
			if (stream == null) {
				return "unknown";
			}
			var props = new Properties();
			props.load(stream);
			return props.getProperty("version", "unknown");
		} catch (IOException e) {
			return "unknown";
		}
	}

	// -------------------------------------------------------------------------
	// Lifecycle
	// -------------------------------------------------------------------------

	@Override
	public void preinit(NuclrPluginContext context) {
		this.context = context;
		this.currentResource = GcpResource.root();
		log.info("GCP file panel plugin loaded");
	}

	@Override
	public void init() {
		// Nothing to initialise; projects are fetched lazily on first navigation.
	}

	@Override
	public NuclrPluginContext getContext() {
		return context;
	}

	@Override
	public void unload() {
		context = null;
		cachedProjects = null;
		log.info("GCP file panel plugin unloaded");
	}

	@Override
	public void closeResource() {
		// No long-lived session to close; gcloud is invoked per listing.
	}

	// -------------------------------------------------------------------------
	// Focus
	// -------------------------------------------------------------------------

	@Override
	public boolean onFocusGained() {
		focused = true;
		return true;
	}

	@Override
	public void onFocusLost() {
		focused = false;
	}

	@Override
	public boolean isFocused() {
		return focused;
	}

	// -------------------------------------------------------------------------
	// Drive selector ("GCP") + routing
	// -------------------------------------------------------------------------

	@Override
	public MenuItemsHolder getPluginMenuItems() {

		var item = new MenuItem();
		item.setText("GCP");
		item.setUuid(GcpResource.ROOT_UUID);
		item.setPath(GcpResource.root());

		var holder = new MenuItemsHolder();
		holder.setTitle("Google Cloud Platform");
		holder.setMenuItems(List.of(item));
		return holder;
	}

	@Override
	public boolean supports(NuclrResource resource) {
		return GcpResource.isGcpResource(resource);
	}

	@Override
	public NuclrResource getCurrentResource() {
		return currentResource;
	}

	// -------------------------------------------------------------------------
	// Listing
	// -------------------------------------------------------------------------

	@Override
	public NuclrResourceData openResource(NuclrResource resourceToOpen, AtomicBoolean cancelled) {
		return openResource(resourceToOpen, cancelled, null);
	}

	@Override
	public NuclrResourceData openResource(NuclrResource resourceToOpen, AtomicBoolean cancelled, EntrySink sink) {

		if (resourceToOpen == null || !supports(resourceToOpen)) {
			return null;
		}
		if (cancelled != null && cancelled.get()) {
			return null;
		}

		if (GcpResource.isRoot(resourceToOpen)) {
			// Adopt a clean root (the incoming resource may be the ".." entry) so the
			// location bar / window title don't show "..".
			this.currentResource = GcpResource.root();
			return listProjects(cancelled, sink);
		}

		// A project: per-project contents are not browsable yet, so show only the
		// ".." that returns to the project list.
		this.currentResource = resourceToOpen;
		return projectContents(sink);
	}

	/** Build the project listing for the GCP root, streaming entries into {@code sink}. */
	private NuclrResourceData listProjects(AtomicBoolean cancelled, EntrySink sink) {

		var data = new NuclrResourceData();
		data.setColumnNames(COLUMNS);
		if (sink != null) {
			sink.columns(COLUMNS);
		}

		List<GcpProject> projects = projects();
		if (projects == null) {
			// Hard error already surfaced to the user via GcpErrorDialog; show an empty root.
			return data;
		}

		for (GcpProject project : projects) {
			if (cancelled != null && cancelled.get()) {
				break;
			}
			var entry = GcpResource.project(project);
			data.getEntries().add(entry);
			if (sink != null) {
				sink.add(entry);
			}
		}

		log.info("GCP project listing: {} project(s)", data.getEntries().size());
		return data;
	}

	/** A project shows only the synthetic ".." back to the project list. */
	private NuclrResourceData projectContents(EntrySink sink) {

		var data = new NuclrResourceData();
		data.setColumnNames(COLUMNS);
		if (sink != null) {
			sink.columns(COLUMNS);
		}

		var parent = GcpResource.parent();
		data.getEntries().add(parent);
		if (sink != null) {
			sink.add(parent);
		}
		return data;
	}

	/**
	 * Return the accessible projects, using a short-lived cache to avoid re-running
	 * gcloud on every navigation. Returns {@code null} on a hard error (gcloud missing,
	 * not authenticated, timeout, command failure) — already surfaced via
	 * {@link GcpErrorDialog}. "No projects accessible" is represented as an empty list.
	 */
	private List<GcpProject> projects() {

		var cached = cachedProjects;
		if (cached != null && System.currentTimeMillis() - cachedAtMs < CACHE_TTL_MS) {
			return cached;
		}

		GcpProjectRepository.Result result = repository.listProjects();
		return switch (result) {
			case GcpProjectRepository.Result.Ok ok -> cache(ok.projects());
			case GcpProjectRepository.Result.Err err -> {
				if (err.error() instanceof GcpError.NoProjectsAccessible) {
					yield cache(List.of());
				}
				log.warn("GCP project list failed: {}", err.error());
				GcpErrorDialog.show(err.error());
				yield null;
			}
		};
	}

	private List<GcpProject> cache(List<GcpProject> projects) {
		cachedProjects = projects;
		cachedAtMs = System.currentTimeMillis();
		return projects;
	}

	// -------------------------------------------------------------------------
	// Display text
	// -------------------------------------------------------------------------

	@Override
	public String getCurrentLocationDisplayText() {
		if (GcpResource.isProject(currentResource)) {
			return "GCP: " + currentResource.getName();
		}
		return "GCP: Projects";
	}

	@Override
	public String getWindowTitle() {
		return getCurrentLocationDisplayText();
	}

	@Override
	public String getSelectionSummaryText(List<NuclrResource> selectedResources) {
		if (selectedResources == null || selectedResources.isEmpty()) {
			return getCurrentLocationDisplayText();
		}
		if (selectedResources.size() == 1) {
			return selectedResources.get(0).getName();
		}
		return selectedResources.size() + " projects selected";
	}
}
