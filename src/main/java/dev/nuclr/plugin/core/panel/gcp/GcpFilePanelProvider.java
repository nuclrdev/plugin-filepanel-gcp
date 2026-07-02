package dev.nuclr.plugin.core.panel.gcp;

import java.awt.Desktop;
import java.awt.Frame;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import dev.nuclr.platform.plugin.BaseNuclrPlugin;
import dev.nuclr.platform.plugin.FilePanelNuclrPlugin;
import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.platform.plugin.NuclrMenuResource;
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

	/** Columns shown for the secret listing under a project's Secret Manager service. */
	private static final List<String> SECRET_COLUMNS = List.of("Name", "Created", "Locations");

	/** How many objects to load per page (each "Load more…" fetches one more page). */
	private static final int OBJECT_PAGE_SIZE = 1000;

	/**
	 * {@code act} action that drops the cached listing for the currently-open level so the next
	 * navigation re-fetches it (see {@link #act}). Cached listings otherwise persist across
	 * restarts via {@link GcpDiskCache} rather than expiring on a timer.
	 */
	private static final String ACTION_REFRESH_PANEL = "refresh.panel";

	/**
	 * {@code act} action dispatched by the host when the user activates (opens) an entry. For a
	 * leaf GCS object this opens the object's Cloud Console page in the default browser rather
	 * than downloading it (quick view — Ctrl+Q — still downloads for local preview).
	 */
	private static final String ACTION_PATH_OPENED = "filepanel.path.opened";

	/**
	 * {@code act} action dispatched by the host for the F5 "Copy" function key. The selected GCS
	 * objects are copied into the other panel's current folder (see {@link GcsCopyService}).
	 */
	private static final String ACTION_COPY = "filepanel.copy";

	/**
	 * {@code act} action a source plugin dispatches to this plugin to hand off a copy it initiated
	 * (F5 in the other panel). The source files are uploaded into the bucket listing open here (see
	 * {@link GcsCopyService#acceptCopy}).
	 */
	private static final String ACTION_ACCEPT_COPY = "accept.copy";

	/**
	 * {@code act} action dispatched by the host for the F8 "Delete" function key. The selected (or
	 * focused) GCS objects are removed from their bucket after confirmation (see {@link GcsDeleteService}).
	 */
	private static final String ACTION_DELETE = "filepanel.delete";

	/**
	 * {@code act} action dispatched by the host for the F7 "Make Folder" function key. Prompts for a
	 * name and creates a Cloud Storage "folder" in the current bucket listing (see
	 * {@link GcsMakeFolderService}). Only meaningful inside a bucket.
	 */
	private static final String ACTION_MAKE_FOLDER = "filepanel.makeFolder";

	/**
	 * {@code act} action dispatched by the host for the Alt+F7 "Find" function key. Opens the
	 * find-by-name dialog scoped to the current bucket/folder (see {@link GcsFindDialog}).
	 */
	private static final String ACTION_FIND = "find";

	private final String uuid = UUID.randomUUID().toString();
	private final GcpProjectRepository repository = new GcpProjectRepository();
	private final GcsBucketRepository bucketRepository = new GcsBucketRepository();
	private final GcpSecretRepository secretRepository = new GcpSecretRepository();

	private NuclrPluginContext context;
	private boolean focused;
	private NuclrResource currentResource;

	// In-memory hot layer over the disk cache so re-entering a listing (e.g. ".." back from a
	// project, or switching between GCS and Pub/Sub) does not touch disk on every navigation.
	// Populated from disk / gcloud on first read; dropped for the open level on "refresh.panel".
	private volatile List<GcpProject> cachedProjects;

	/** Per-project bucket cache, keyed by project id. */
	private final Map<String, List<GcsBucket>> bucketCache = new ConcurrentHashMap<>();

	// Active object listing: a live, lazily-consumed gcloud stream plus the rows shown so far.
	// One listing is active at a time; navigating away closes the pager (see openResource).
	private GcsObjectPager pager;
	private String pagerKey;
	private final List<NuclrResource> pagerRows = new ArrayList<>();
	// Object models accumulated across pages of the active listing, persisted to the disk cache
	// once the listing is fully loaded (see emitNextPage).
	private final List<GcsObject> pagerObjects = new ArrayList<>();

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
		GcsEndpoints.clear();
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

	/**
	 * Bottom-bar function keys for the GCP panel: F5 "Copy" (copies the selected Cloud Storage
	 * object(s) into the other panel's folder, see {@link GcsCopyService}), F7 "Make Folder" (creates
	 * a folder in the current bucket, see {@link GcsMakeFolderService}), and F8 "Delete" (removes the
	 * selected object(s) from their bucket, see {@link GcsDeleteService}).
	 */
	@Override
	public List<NuclrMenuResource> menuItems(NuclrResource resource) {
		return List.of(
				new NuclrMenuResource("Copy", "F5", ACTION_COPY),
				new NuclrMenuResource("Make Folder", "F7", ACTION_MAKE_FOLDER),
				new NuclrMenuResource("Delete", "F8", ACTION_DELETE),
				new NuclrMenuResource("Find", "Alt+F7", ACTION_FIND));
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

		if (GcpResource.isSearchResults(resourceToOpen)) {
			this.currentResource = resourceToOpen;
			return listSearchResults(resourceToOpen, cancelled, sink);
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
			String serviceType = GcpResource.serviceType(resourceToOpen);
			if (GcpResource.SERVICE_GCS.equals(serviceType)) {
				this.currentResource = GcpResource.gcsService(projectId);
				return listBuckets(projectId, cancelled, sink);
			}
			if (GcpResource.SERVICE_PUBSUB.equals(serviceType)) {
				this.currentResource = GcpResource.pubsubService(projectId);
				return listPubsub(projectId, sink);
			}
			if (GcpResource.SERVICE_SECRET.equals(serviceType)) {
				this.currentResource = GcpResource.secretManagerService(projectId);
				return listSecrets(projectId, cancelled, sink);
			}
			// Any other (future) service is not browsable yet; show only the "..".
			this.currentResource = GcpResource.pubsubService(projectId);
			return serviceStub(projectId, sink);
		}

		if (GcpResource.isPubsubCategory(resourceToOpen)) {
			// Topics / Subscriptions are not browsable yet; show only the ".." back to Pub/Sub.
			String projectId = GcpResource.projectId(resourceToOpen);
			this.currentResource = GcpResource.PUBSUB_SUBSCRIPTIONS.equals(GcpResource.pubsubCategory(resourceToOpen))
					? GcpResource.pubsubSubscriptions(projectId)
					: GcpResource.pubsubTopics(projectId);
			return pubsubCategoryStub(projectId, sink);
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

	/** A project lists the GCP services it exposes ({@code ..}, GCS, Pub/Sub, Secret Manager). */
	private NuclrResourceData listServices(String projectId, EntrySink sink) {

		var data = new NuclrResourceData();
		data.setColumnNames(SERVICE_COLUMNS);
		if (sink != null) {
			sink.columns(SERVICE_COLUMNS);
		}

		add(data, sink, GcpResource.parent()); // ".." back to the project list
		add(data, sink, GcpResource.gcsService(projectId));
		add(data, sink, GcpResource.pubsubService(projectId));
		add(data, sink, GcpResource.secretManagerService(projectId));
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
			// Remember each bucket's region so object downloads can use the regional endpoint.
			GcsEndpoints.recordLocation(bucket.name(), bucket.location());
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

	/** The Pub/Sub service lists its two categories: {@code ..}, Topics, Subscriptions. */
	private NuclrResourceData listPubsub(String projectId, EntrySink sink) {

		var data = new NuclrResourceData();
		data.setColumnNames(SERVICE_COLUMNS);
		if (sink != null) {
			sink.columns(SERVICE_COLUMNS);
		}

		add(data, sink, GcpResource.parentToProject(projectId)); // ".." back to the service list
		add(data, sink, GcpResource.pubsubTopics(projectId));
		add(data, sink, GcpResource.pubsubSubscriptions(projectId));
		return data;
	}

	/** A not-yet-browsable Pub/Sub category shows only the synthetic ".." back to Pub/Sub. */
	private NuclrResourceData pubsubCategoryStub(String projectId, EntrySink sink) {

		var data = new NuclrResourceData();
		data.setColumnNames(SERVICE_COLUMNS);
		if (sink != null) {
			sink.columns(SERVICE_COLUMNS);
		}

		add(data, sink, GcpResource.parentToPubsub(projectId));
		return data;
	}

	/** The Secret Manager service lists the project's secrets ({@code ..} then each secret). */
	private NuclrResourceData listSecrets(String projectId, AtomicBoolean cancelled, EntrySink sink) {

		var data = new NuclrResourceData();
		data.setColumnNames(SECRET_COLUMNS);
		if (sink != null) {
			sink.columns(SECRET_COLUMNS);
		}

		add(data, sink, GcpResource.parentToProject(projectId)); // ".." back to the service list

		List<GcpSecret> secrets = secrets(projectId);
		if (secrets == null) {
			// Hard error already surfaced via GcpErrorDialog; show just the "..".
			return data;
		}

		for (GcpSecret secret : secrets) {
			if (cancelled != null && cancelled.get()) {
				break;
			}
			add(data, sink, GcpResource.secret(projectId, secret));
		}
		log.info("Secret Manager listing for {}: {} secret(s)", projectId, secrets.size());
		return data;
	}

	/**
	 * Return a project's Secret Manager secrets, or {@code null} on a hard error (already surfaced
	 * via {@link GcpErrorDialog}); "no secrets" is an empty list. Fetched live (not cached).
	 */
	private List<GcpSecret> secrets(String projectId) {
		GcpSecretRepository.Result result = secretRepository.listSecrets(projectId);
		return switch (result) {
			case GcpSecretRepository.Result.Ok ok -> ok.secrets();
			case GcpSecretRepository.Result.Err err -> {
				log.warn("Secret Manager list failed for {}: {}", projectId, err.error());
				GcpErrorDialog.show(err.error());
				yield null;
			}
		};
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

		// The user is heading toward objects; warm the access token and a TLS connection to the
		// bucket's endpoint now, so the first quick-view download skips both the one-time gcloud
		// token fetch and the cold handshake.
		warmGcs(bucket);

		// Row 0 is always ".." (up one prefix level, or back to the bucket list at the root).
		pagerRows.clear();
		pagerObjects.clear();
		NuclrResource parent = GcpResource.objectParent(projectId, bucket, prefix);
		pagerRows.add(parent);
		add(data, sink, parent);

		// A restart-persistent cache hit renders the whole listing without gcloud, so there is
		// no live stream and no "Load more…" — the cached listing is the complete set of children.
		List<GcsObject> cached = GcpDiskCache.loadObjects(bucket, prefix);
		if (cached != null) {
			for (GcsObject object : cached) {
				if (cancelled != null && cancelled.get()) {
					break;
				}
				NuclrResource entry = objectRow(projectId, bucket, prefix, object);
				pagerRows.add(entry);
				add(data, sink, entry);
			}
			log.info("GCS object listing gs://{}/{}: {} row(s) from disk cache", bucket, prefix, cached.size());
			return data;
		}

		long openNanos = System.nanoTime();
		try {
			pager = GcsObjectPager.open(bucket, prefix);
			pagerKey = pagerKey(bucket, prefix);
		} catch (GcsObjectPager.GcsListException e) {
			log.warn("GCS object list failed for gs://{}/{}: {}", bucket, prefix, e.error());
			GcpErrorDialog.show(e.error());
			closePager();
			return data; // just ".."
		}
		log.info("GCS object stream opened for gs://{}/{} in {} ms", bucket, prefix, millisSince(openNanos));

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

		long pageNanos = System.nanoTime();
		List<GcsObject> page = pager.nextPage(OBJECT_PAGE_SIZE, cancelled);
		long pageMs = millisSince(pageNanos);
		for (GcsObject object : page) {
			pagerObjects.add(object);
			NuclrResource entry = objectRow(projectId, bucket, prefix, object);
			pagerRows.add(entry);
			add(data, sink, entry);
		}

		if (pager.hasMore()) {
			// Transient continuation row — recomputed on each render, not accumulated.
			add(data, sink, GcpResource.loadMore(projectId, bucket, prefix));
		} else {
			// Listing complete: persist the full child set for instant re-open, then free the process.
			GcpDiskCache.saveObjects(bucket, prefix, List.copyOf(pagerObjects));
			closePager(); // rows already rendered
		}
		log.info("GCS object listing gs://{}/{}: +{} row(s) in {} ms, more={}",
				bucket, prefix, page.size(), pageMs, pager != null && pager.hasMore());
	}

	/** An object listing row: a navigable sub-folder for a prefix, else a downloadable leaf object. */
	private static NuclrResource objectRow(String projectId, String bucket, String prefix, GcsObject object) {
		return object.folder()
				? GcpResource.objectDir(projectId, bucket, prefix + object.name(), object.name())
				: GcpResource.object(projectId, bucket, prefix + object.name(), object);
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
		pagerObjects.clear();
	}

	private static String pagerKey(String bucket, String prefix) {
		return bucket + ' ' + prefix;
	}

	/** Off-thread, prime the access token and a TLS connection so the first quick view is warm. */
	private static void warmGcs(String bucket) {
		Thread.ofVirtual().start(() -> GcsObjectDownloader.warmConnection(bucket));
	}

	private static long millisSince(long startNanos) {
		return (System.nanoTime() - startNanos) / 1_000_000L;
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
	 * Return the accessible projects. Served from the in-memory hot layer, then the
	 * restart-persistent {@link GcpDiskCache}, and only then from a live {@code gcloud} run
	 * (whose result is persisted). Returns {@code null} on a hard error (gcloud missing,
	 * not authenticated, timeout, command failure) — already surfaced via
	 * {@link GcpErrorDialog}. "No projects accessible" is represented as an empty list.
	 */
	private List<GcpProject> projects() {

		var cached = cachedProjects;
		if (cached != null) {
			return cached;
		}

		List<GcpProject> disk = GcpDiskCache.loadProjects();
		if (disk != null) {
			cachedProjects = disk;
			return disk;
		}

		GcpProjectRepository.Result result = repository.listProjects();
		return switch (result) {
			case GcpProjectRepository.Result.Ok ok -> {
				GcpDiskCache.saveProjects(ok.projects());
				cachedProjects = ok.projects();
				yield ok.projects();
			}
			case GcpProjectRepository.Result.Err err -> {
				if (err.error() instanceof GcpError.NoProjectsAccessible) {
					// Transient-looking "no projects": cache in memory only so a restart re-queries.
					cachedProjects = List.of();
					yield List.of();
				}
				log.warn("GCP project list failed: {}", err.error());
				GcpErrorDialog.show(err.error());
				yield null;
			}
		};
	}

	/**
	 * Return a project's Cloud Storage buckets. Served from the in-memory hot layer, then the
	 * restart-persistent {@link GcpDiskCache}, and only then from a live {@code gcloud} run
	 * (whose result is persisted). Returns {@code null} on a hard error (already surfaced via
	 * {@link GcpErrorDialog}); "no buckets" is an empty list.
	 */
	private List<GcsBucket> buckets(String projectId) {

		List<GcsBucket> cached = bucketCache.get(projectId);
		if (cached != null) {
			return cached;
		}

		List<GcsBucket> disk = GcpDiskCache.loadBuckets(projectId);
		if (disk != null) {
			bucketCache.put(projectId, disk);
			return disk;
		}

		GcsBucketRepository.Result result = bucketRepository.listBuckets(projectId);
		return switch (result) {
			case GcsBucketRepository.Result.Ok ok -> {
				GcpDiskCache.saveBuckets(projectId, ok.buckets());
				bucketCache.put(projectId, ok.buckets());
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
	 * Handles panel actions. On {@value #ACTION_REFRESH_PANEL} only the cached listing for the
	 * <em>currently open</em> level is dropped — projects at the root, that project's buckets in
	 * the GCS service, or that {@code (bucket, prefix)}'s objects inside a bucket — so the host's
	 * follow-up reload re-queries just what the user is looking at. Other levels keep their
	 * persistent cache. Other actions are ignored.
	 */
	@Override
	public void act(BaseNuclrPlugin other, String actionType, List<NuclrResource> selectedResources,
			NuclrResource focusedResource, Map<String, Object> data, NuclrPluginCallback callback) {

		if (ACTION_PATH_OPENED.equals(actionType)) {
			openInConsole(focusedResource);
			return;
		}

		if (ACTION_COPY.equals(actionType)) {
			new GcsCopyService().copy(other, selectedResources, focusedResource, context, uuid);
			return;
		}

		if (ACTION_ACCEPT_COPY.equals(actionType)) {
			new GcsCopyService().acceptCopy(
					selectedResources, focusedResource, currentResource, context, uuid, currentListingByName());
			return;
		}

		if (ACTION_MAKE_FOLDER.equals(actionType)) {
			makeFolder(data);
			return;
		}

		if (ACTION_FIND.equals(actionType)) {
			openFindDialog();
			return;
		}

		if (ACTION_DELETE.equals(actionType)) {
			int deleted = new GcsDeleteService().delete(selectedResources, focusedResource);
			if (deleted > 0) {
				// The deleted objects belong to the currently open listing; drop its cached copy
				// and reload this panel so the removed rows disappear (their marks drop with them).
				if (GcpResource.isBucket(currentResource) || GcpResource.isObjectDir(currentResource)) {
					GcpDiskCache.clearObjects(
							GcpResource.bucketName(currentResource), GcpResource.objectPrefix(currentResource));
				}
				if (context != null && context.getEventBus() != null) {
					context.getEventBus().emit("refresh.plugin.file.panel", Map.of("plugin.uuid", uuid), null);
				}
			}
			return;
		}

		if (!ACTION_REFRESH_PANEL.equals(actionType)) {
			return;
		}

		if (GcpResource.isBucket(currentResource) || GcpResource.isObjectDir(currentResource)) {
			String bucket = GcpResource.bucketName(currentResource);
			String prefix = GcpResource.objectPrefix(currentResource);
			GcpDiskCache.clearObjects(bucket, prefix);
			closePager(); // drop any live stream for this listing so the reload re-fetches it
			log.info("GCP object listing gs://{}/{} invalidated on '{}'", bucket, prefix, actionType);
		} else if (GcpResource.isService(currentResource)
				&& GcpResource.SERVICE_GCS.equals(GcpResource.serviceType(currentResource))) {
			String projectId = GcpResource.projectId(currentResource);
			bucketCache.remove(projectId);
			GcpDiskCache.clearBuckets(projectId);
			log.info("GCS bucket listing for {} invalidated on '{}'", projectId, actionType);
		} else if (GcpResource.isRoot(currentResource)) {
			cachedProjects = null;
			GcpDiskCache.clearProjects();
			log.info("GCP project listing invalidated on '{}'", actionType);
		}
	}

	/**
	 * Prompts for a folder name and creates it in the current bucket listing. Only valid inside a
	 * bucket (a bucket root or a sub-folder); requests a panel reload (via {@code result.refresh})
	 * so the new folder appears, with the cursor placed on it.
	 */
	private void makeFolder(Map<String, Object> data) {
		if (!GcpResource.isBucket(currentResource) && !GcpResource.isObjectDir(currentResource)) {
			SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
					"Open a bucket to create a folder.", "Make Folder", JOptionPane.INFORMATION_MESSAGE));
			return;
		}

		String projectId = GcpResource.projectId(currentResource);
		String bucket = GcpResource.bucketName(currentResource);
		String prefix = GcpResource.objectPrefix(currentResource);

		String created = new GcsMakeFolderService().makeFolder(bucket, prefix, currentListingNames());
		if (created == null) {
			return; // cancelled, invalid, duplicate, or failed — dialog already shown
		}

		// Drop the cached listing so the reload re-fetches and shows the new placeholder folder.
		GcpDiskCache.clearObjects(bucket, prefix);
		if (data != null) {
			try {
				data.put("result.refresh", true);
				data.put("result.refresh.selected.resource",
						GcpResource.objectDir(projectId, bucket, prefix + created + "/", created + "/"));
			} catch (UnsupportedOperationException ignored) {
				log.debug("Make-folder payload is immutable; new folder will not be pre-selected.");
			}
		}
	}

	/** Display names of the entries in the current object listing (excluding ".."), for duplicate checks. */
	private Set<String> currentListingNames() {
		Set<String> names = new HashSet<>();
		for (NuclrResource row : pagerRows) {
			if (row != null && !"..".equals(row.getName())) {
				names.add(row.getName());
			}
		}
		return names;
	}

	/** Display name → resource for the current object listing (excluding ".."), for the upload conflict check. */
	private Map<String, NuclrResource> currentListingByName() {
		Map<String, NuclrResource> byName = new HashMap<>();
		for (NuclrResource row : pagerRows) {
			if (row != null && !"..".equals(row.getName())) {
				byName.put(row.getName(), row);
			}
		}
		return byName;
	}

	// -------------------------------------------------------------------------
	// Find (Alt+F7): name-only search under the current bucket/folder
	// -------------------------------------------------------------------------

	/** Open the find dialog scoped to the current bucket/folder and, on submit, run the search. */
	private void openFindDialog() {
		if (!GcpResource.isBucket(currentResource) && !GcpResource.isObjectDir(currentResource)) {
			SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
					"Open a bucket to search for files.", "Find files", JOptionPane.INFORMATION_MESSAGE));
			return;
		}
		NuclrResource origin = currentResource;
		GcsFindRequest request = GcsFindDialog.show(
				GcpResource.projectId(origin), GcpResource.bucketName(origin), GcpResource.objectPrefix(origin));
		if (request == null) {
			return; // cancelled
		}

		GcsFindResultsWindow results = new GcsFindResultsWindow(mainWindow(), request,
				this::navigateToResult,
				hits -> openResultsInTempPanel(hits, request, origin));
		GcsFindService.SearchHandle handle = new GcsFindService().search(request, results);
		results.bind(handle);
		results.setVisible(true);
	}

	/** Navigate the panel to a search hit: open its folder and put the cursor on it. */
	private void navigateToResult(NuclrResource resource) {
		if (context == null || context.getEventBus() == null) {
			return;
		}
		var payload = new HashMap<String, Object>();
		if (GcpResource.isObject(resource)) {
			String key = GcpResource.objectKey(resource);
			String parent = key == null ? "" : GcpResource.parentPrefix(key);
			payload.put("resource", GcpResource.objectDir(
					GcpResource.projectId(resource), GcpResource.bucketName(resource), parent, ""));
			payload.put("selectChild", resource);
		} else if (GcpResource.isObjectDir(resource)) {
			payload.put("resource", resource);
		} else {
			return;
		}
		context.getEventBus().emit(this, ACTION_PATH_OPENED, payload);
	}

	/** Send the whole result set to a temporary "search results" panel in the focused pane. */
	private void openResultsInTempPanel(List<NuclrResource> hits, GcsFindRequest request, NuclrResource origin) {
		if (context == null || context.getEventBus() == null) {
			return;
		}
		var payload = new HashMap<String, Object>();
		payload.put("resource", GcpResource.searchResults(hits, request.title(), origin));
		context.getEventBus().emit(this, ACTION_PATH_OPENED, payload);
	}

	/** List a "search results" temp panel: a synthetic ".." back to the origin, then the hits. */
	private NuclrResourceData listSearchResults(NuclrResource root, AtomicBoolean cancelled, EntrySink sink) {
		var data = newObjectData(sink);

		NuclrResource origin = GcpResource.searchOrigin(root);
		if (GcpResource.isBucket(origin) || GcpResource.isObjectDir(origin)) {
			add(data, sink, GcpResource.objectDir(GcpResource.projectId(origin),
					GcpResource.bucketName(origin), GcpResource.objectPrefix(origin), ".."));
		}

		for (NuclrResource hit : GcpResource.searchHits(root)) {
			if (cancelled != null && cancelled.get()) {
				break;
			}
			// Show the full gs:// path in the Name column so results across folders are unambiguous.
			hit.getMetadata().put("Name", hit.getFullPath() != null ? hit.getFullPath() : hit.getName());
			add(data, sink, hit);
		}
		return data;
	}

	/** The top-level commander frame, used to anchor Find windows independently of transient dialogs. */
	private static Window mainWindow() {
		for (Frame frame : Frame.getFrames()) {
			if (frame.isShowing()) {
				return frame;
			}
		}
		return KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
	}

	/**
	 * Opens the Cloud Console "object details" page for {@code resource} in the default browser,
	 * off the EDT. No-op if the resource is not a GCS object or the platform has no browse support.
	 */
	private static void openInConsole(NuclrResource resource) {
		String url = GcpResource.objectConsoleUrl(resource);
		if (url == null) {
			return;
		}
		Thread.ofVirtual().start(() -> {
			try {
				if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
					Desktop.getDesktop().browse(URI.create(url));
					log.info("Opened GCS object in Cloud Console: {}", url);
				} else {
					log.warn("Desktop browse not supported; cannot open {}", url);
				}
			} catch (IOException | RuntimeException e) {
				log.warn("Failed to open {} in browser: {}", url, e.getMessage());
			}
		});
	}

	// -------------------------------------------------------------------------
	// Display text
	// -------------------------------------------------------------------------

	@Override
	public String getCurrentLocationDisplayText() {
		if (GcpResource.isSearchResults(currentResource)) {
			return "GCP: " + GcpResource.searchTitle(currentResource);
		}
		if (GcpResource.isBucket(currentResource) || GcpResource.isObjectDir(currentResource)) {
			return "GCP: " + GcpResource.projectId(currentResource) + " / GCS / gs://"
					+ GcpResource.bucketName(currentResource) + "/" + GcpResource.objectPrefix(currentResource);
		}
		if (GcpResource.isPubsubCategory(currentResource)) {
			return "GCP: " + GcpResource.projectId(currentResource) + " / Pub/Sub / " + currentResource.getName();
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
