package dev.nuclr.plugin.core.panel.gcp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.nuclr.platform.plugin.BaseNuclrPlugin;
import dev.nuclr.platform.plugin.FilePanelNuclrPlugin;
import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import lombok.extern.slf4j.Slf4j;

/**
 * GCP file panel plugin (platform-sdk 3.x).
 *
 * <p>Contributes a single <b>GCP</b> entry to the Alt+F1/Alt+F2 drive selector. Opening
 * it lists the GCP projects the current user can access, queried live via the
 * {@code gcloud} CLI ({@code gcloud projects list}). Each project is shown as a navigable
 * folder; entering one lists its GCP services (GCS, Pub/Sub). Entering GCS lists the
 * project's Cloud Storage buckets ({@code gcloud storage buckets list}); other services
 * are not browsable yet.
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
	private static final List<String> PROJECT_COLUMNS = List.of("Name", "Project Name", "Number", "State");

	/** Columns shown for the service listing under a project (GCS, Pub/Sub). */
	private static final List<String> SERVICE_COLUMNS = List.of("Name", "Description");

	/** Columns shown for the Cloud Storage bucket listing under a project's GCS service. */
	private static final List<String> BUCKET_COLUMNS = List.of(
			"Name", "Created", "Location type", "Location",
			"Default storage class", "Last modified", "Public Access");

	/** Columns shown for the object listing inside a bucket. */
	private static final List<String> OBJECT_COLUMNS = List.of("Name", "Size", "Storage class", "Updated");

	/** How many objects to load per page (each "Load more…" fetches one more page). */
	private static final int OBJECT_PAGE_SIZE = 1000;

	/** How long a successful project/bucket list stays valid before {@code gcloud} is re-run. */
	private static final long CACHE_TTL_MS = 5 * 60_000L;

	/** {@code act} action that forces every cached listing to be re-fetched on next navigation. */
	private static final String ACTION_REFRESH_PANEL = "refresh.panel";

	private final String uuid = UUID.randomUUID().toString();
	private final GcpProjectRepository repository = new GcpProjectRepository();
	private final GcsBucketRepository bucketRepository = new GcsBucketRepository();

	private NuclrPluginContext context;
	private boolean focused;
	private NuclrResource currentResource;

	// Short-lived caches so re-entering a listing (e.g. ".." back from a project, or
	// switching between GCS and Pub/Sub) does not re-run gcloud on every navigation.
	// Written from the background read thread; cleared on the "refresh.panel" action.
	private volatile List<GcpProject> cachedProjects;
	private volatile long cachedAtMs;

	/** Per-project bucket cache, keyed by project id. */
	private record CachedBuckets(List<GcsBucket> buckets, long atMs) {}
	private final Map<String, CachedBuckets> bucketCache = new ConcurrentHashMap<>();

	// Active object listing: a live, lazily-consumed gcloud stream plus the rows shown so far.
	// One listing is active at a time; navigating away closes the pager (see openResource).
	private GcsObjectPager pager;
	private String pagerKey;
	private final List<NuclrResource> pagerRows = new ArrayList<>();

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
		bucketCache.clear();
		closePager();
		GcsTempFiles.cleanup();
		log.info("GCP file panel plugin unloaded");
	}

	@Override
	public void closeResource() {
		// Release any live object-listing stream; project/bucket listings are per-call.
		closePager();
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

		// Any navigation other than "Load more…" abandons the active object listing, so
		// release its held gcloud process before opening something else.
		if (!GcpResource.isLoadMore(resourceToOpen)) {
			closePager();
		}

		if (GcpResource.isRoot(resourceToOpen)) {
			// Adopt a clean root (the incoming resource may be the ".." entry) so the
			// location bar / window title don't show "..".
			this.currentResource = GcpResource.root();
			return listProjects(cancelled, sink);
		}

		if (GcpResource.isProject(resourceToOpen)) {
			// Rebuild a clean project ref (the incoming resource may be the ".." entry) so
			// the location bar shows the project id rather than "..".
			String projectId = GcpResource.projectId(resourceToOpen);
			this.currentResource = GcpResource.projectRef(projectId);
			return listServices(projectId, sink);
		}

		if (GcpResource.isService(resourceToOpen)) {
			// Rebuild a clean service node (the incoming resource may be the ".." back from a
			// bucket) so the location bar shows the service name rather than "..".
			String projectId = GcpResource.projectId(resourceToOpen);
			if (GcpResource.SERVICE_GCS.equals(GcpResource.serviceType(resourceToOpen))) {
				this.currentResource = GcpResource.gcsService(projectId);
				return listBuckets(projectId, cancelled, sink);
			}
			this.currentResource = GcpResource.pubsubService(projectId);
			// Other services (e.g. Pub/Sub) are not browsable yet; show only the "..".
			return serviceStub(projectId, sink);
		}

		// Entering a bucket (prefix "") or a sub-folder: list its immediate objects/folders.
		if (GcpResource.isBucket(resourceToOpen) || GcpResource.isObjectDir(resourceToOpen)) {
			String projectId = GcpResource.projectId(resourceToOpen);
			String bucket = GcpResource.bucketName(resourceToOpen);
			String prefix = GcpResource.objectPrefix(resourceToOpen);
			this.currentResource = GcpResource.objectDir(projectId, bucket, prefix, locationName(bucket, prefix));
			return listObjects(projectId, bucket, prefix, cancelled, sink);
		}

		// "Load more…": fetch the next page of the current listing and re-render it.
		if (GcpResource.isLoadMore(resourceToOpen)) {
			String projectId = GcpResource.projectId(resourceToOpen);
			String bucket = GcpResource.bucketName(resourceToOpen);
			String prefix = GcpResource.objectPrefix(resourceToOpen);
			this.currentResource = GcpResource.objectDir(projectId, bucket, prefix, locationName(bucket, prefix));
			return loadMoreObjects(projectId, bucket, prefix, cancelled, sink);
		}

		return null;
	}

	/** Build the project listing for the GCP root, streaming entries into {@code sink}. */
	private NuclrResourceData listProjects(AtomicBoolean cancelled, EntrySink sink) {

		var data = new NuclrResourceData();
		data.setColumnNames(PROJECT_COLUMNS);
		if (sink != null) {
			sink.columns(PROJECT_COLUMNS);
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

	/** A project lists the GCP services it exposes ({@code ..}, GCS, Pub/Sub). */
	private NuclrResourceData listServices(String projectId, EntrySink sink) {

		var data = new NuclrResourceData();
		data.setColumnNames(SERVICE_COLUMNS);
		if (sink != null) {
			sink.columns(SERVICE_COLUMNS);
		}

		add(data, sink, GcpResource.parent()); // ".." back to the project list
		add(data, sink, GcpResource.gcsService(projectId));
		add(data, sink, GcpResource.pubsubService(projectId));
		return data;
	}

	/** The GCS service lists the project's Cloud Storage buckets, streaming into {@code sink}. */
	private NuclrResourceData listBuckets(String projectId, AtomicBoolean cancelled, EntrySink sink) {

		var data = new NuclrResourceData();
		data.setColumnNames(BUCKET_COLUMNS);
		if (sink != null) {
			sink.columns(BUCKET_COLUMNS);
		}

		add(data, sink, GcpResource.parentToProject(projectId)); // ".." back to the service list

		List<GcsBucket> buckets = buckets(projectId);
		if (buckets == null) {
			// Hard error already surfaced via GcpErrorDialog; show just the "..".
			return data;
		}

		for (GcsBucket bucket : buckets) {
			if (cancelled != null && cancelled.get()) {
				break;
			}
			add(data, sink, GcpResource.bucket(projectId, bucket));
		}
		log.info("GCS bucket listing for {}: {} bucket(s)", projectId, buckets.size());
		return data;
	}

	/** A not-yet-browsable service shows only the synthetic ".." back to the service list. */
	private NuclrResourceData serviceStub(String projectId, EntrySink sink) {

		var data = new NuclrResourceData();
		data.setColumnNames(SERVICE_COLUMNS);
		if (sink != null) {
			sink.columns(SERVICE_COLUMNS);
		}

		add(data, sink, GcpResource.parentToProject(projectId));
		return data;
	}

	// -------------------------------------------------------------------------
	// Object listing (paged, streamed)
	// -------------------------------------------------------------------------

	/**
	 * Lists the immediate objects and sub-folders of {@code gs://bucket/<prefix>}, loading
	 * only the first page. A fresh gcloud stream is opened and held for subsequent pages.
	 */
	private NuclrResourceData listObjects(
			String projectId, String bucket, String prefix, AtomicBoolean cancelled, EntrySink sink) {

		var data = newObjectData(sink);

		// The user is heading toward objects; warm the access token now so the first quick-view
		// download is a plain HTTPS GET rather than also paying the one-time gcloud token fetch.
		warmAccessToken();

		// Row 0 is always ".." (up one prefix level, or back to the bucket list at the root).
		pagerRows.clear();
		NuclrResource parent = GcpResource.objectParent(projectId, bucket, prefix);
		pagerRows.add(parent);
		add(data, sink, parent);

		try {
			pager = GcsObjectPager.open(bucket, prefix);
			pagerKey = pagerKey(bucket, prefix);
		} catch (GcsObjectPager.GcsListException e) {
			log.warn("GCS object list failed for gs://{}/{}: {}", bucket, prefix, e.error());
			GcpErrorDialog.show(e.error());
			closePager();
			return data; // just ".."
		}

		emitNextPage(projectId, bucket, prefix, data, cancelled, sink);
		return data;
	}

	/**
	 * Re-renders the current listing with one additional page appended. Falls back to a fresh
	 * {@link #listObjects} if the live stream is gone (e.g. caches invalidated meanwhile).
	 */
	private NuclrResourceData loadMoreObjects(
			String projectId, String bucket, String prefix, AtomicBoolean cancelled, EntrySink sink) {

		if (pager == null || !pagerKey(bucket, prefix).equals(pagerKey)) {
			closePager();
			return listObjects(projectId, bucket, prefix, cancelled, sink);
		}

		var data = newObjectData(sink);
		for (NuclrResource row : pagerRows) { // re-emit everything already loaded
			add(data, sink, row);
		}
		emitNextPage(projectId, bucket, prefix, data, cancelled, sink);
		return data;
	}

	/**
	 * Reads the next page from the active pager, appending object/folder rows to the
	 * accumulated listing, and a trailing "Load more…" entry while more remain. When the
	 * listing is exhausted the held gcloud process is released.
	 */
	private void emitNextPage(
			String projectId, String bucket, String prefix, NuclrResourceData data,
			AtomicBoolean cancelled, EntrySink sink) {

		List<GcsObject> page = pager.nextPage(OBJECT_PAGE_SIZE, cancelled);
		for (GcsObject object : page) {
			NuclrResource entry = object.folder()
					? GcpResource.objectDir(projectId, bucket, prefix + object.name(), object.name())
					: GcpResource.object(bucket, prefix + object.name(), object);
			pagerRows.add(entry);
			add(data, sink, entry);
		}

		if (pager.hasMore()) {
			// Transient continuation row — recomputed on each render, not accumulated.
			add(data, sink, GcpResource.loadMore(projectId, bucket, prefix));
		} else {
			closePager(); // listing complete; free the process (rows already rendered)
		}
		log.info("GCS object listing gs://{}/{}: +{} row(s), more={}",
				bucket, prefix, page.size(), pager != null && pager.hasMore());
	}

	private NuclrResourceData newObjectData(EntrySink sink) {
		var data = new NuclrResourceData();
		data.setColumnNames(OBJECT_COLUMNS);
		if (sink != null) {
			sink.columns(OBJECT_COLUMNS);
		}
		return data;
	}

	/** Closes the live object stream (if any) and forgets the accumulated rows. */
	private void closePager() {
		if (pager != null) {
			pager.close();
			pager = null;
		}
		pagerKey = null;
		pagerRows.clear();
	}

	private static String pagerKey(String bucket, String prefix) {
		return bucket + ' ' + prefix;
	}

	/** Pre-fetch the GCS access token off-thread so it is cached before the first quick view. */
	private static void warmAccessToken() {
		Thread.ofVirtual().start(() -> {
			try {
				GcsAccessToken.get(false);
			} catch (IOException ignored) {
				// Best-effort warm-up; the real download will surface any token error.
			}
		});
	}

	/** Display label for an object "directory": the bucket name at the root, else the last segment. */
	private static String locationName(String bucket, String prefix) {
		if (prefix == null || prefix.isEmpty()) {
			return bucket;
		}
		String trimmed = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
		int slash = trimmed.lastIndexOf('/');
		return (slash < 0 ? trimmed : trimmed.substring(slash + 1)) + "/";
	}

	/** Append an entry both to the synchronous result and the streaming sink (if present). */
	private static void add(NuclrResourceData data, EntrySink sink, NuclrResource entry) {
		data.getEntries().add(entry);
		if (sink != null) {
			sink.add(entry);
		}
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

	/**
	 * Return a project's Cloud Storage buckets, using a short-lived per-project cache to
	 * avoid re-running gcloud on every navigation. Returns {@code null} on a hard error
	 * (already surfaced via {@link GcpErrorDialog}); "no buckets" is an empty list.
	 */
	private List<GcsBucket> buckets(String projectId) {

		CachedBuckets cached = bucketCache.get(projectId);
		if (cached != null && System.currentTimeMillis() - cached.atMs() < CACHE_TTL_MS) {
			return cached.buckets();
		}

		GcsBucketRepository.Result result = bucketRepository.listBuckets(projectId);
		return switch (result) {
			case GcsBucketRepository.Result.Ok ok -> {
				bucketCache.put(projectId, new CachedBuckets(ok.buckets(), System.currentTimeMillis()));
				yield ok.buckets();
			}
			case GcsBucketRepository.Result.Err err -> {
				log.warn("GCS bucket list failed for {}: {}", projectId, err.error());
				GcpErrorDialog.show(err.error());
				yield null;
			}
		};
	}

	// -------------------------------------------------------------------------
	// Actions
	// -------------------------------------------------------------------------

	/**
	 * Handles panel actions. On {@value #ACTION_REFRESH_PANEL} the cached project and bucket
	 * listings are dropped so the next navigation re-queries gcloud; other actions are ignored.
	 */
	@Override
	public void act(BaseNuclrPlugin other, String actionType, List<NuclrResource> selectedResources,
			NuclrResource focusedResource, Map<String, Object> data, NuclrPluginCallback callback) {
		if (ACTION_REFRESH_PANEL.equals(actionType)) {
			cachedProjects = null;
			bucketCache.clear();
			log.info("GCP panel caches invalidated on '{}'", actionType);
		}
	}

	// -------------------------------------------------------------------------
	// Display text
	// -------------------------------------------------------------------------

	@Override
	public String getCurrentLocationDisplayText() {
		if (GcpResource.isBucket(currentResource) || GcpResource.isObjectDir(currentResource)) {
			return "GCP: " + GcpResource.projectId(currentResource) + " / GCS / gs://"
					+ GcpResource.bucketName(currentResource) + "/" + GcpResource.objectPrefix(currentResource);
		}
		if (GcpResource.isService(currentResource)) {
			return "GCP: " + GcpResource.projectId(currentResource) + " / " + currentResource.getName();
		}
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
		return selectedResources.size() + " items selected";
	}
}
