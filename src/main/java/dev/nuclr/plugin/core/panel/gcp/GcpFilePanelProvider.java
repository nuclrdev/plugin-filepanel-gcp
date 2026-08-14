package dev.nuclr.plugin.core.panel.gcp;

import dev.nuclr.plugin.core.panel.gcp.gcs.*;
import dev.nuclr.plugin.core.panel.gcp.gke.*;
import dev.nuclr.plugin.core.panel.gcp.pubsub.*;
import dev.nuclr.plugin.core.panel.gcp.secret.*;

import java.awt.Desktop;
import java.awt.Frame;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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



	/** Columns shown for the project listing (cells read from each resource's metadata). */
	private static final List<String> PROJECT_COLUMNS = List.of("Name", "Project Name", "Number", "State");

	/** Columns shown for the service listing under a project (GCS, Pub/Sub). */
	private static final List<String> SERVICE_COLUMNS = List.of("Name", "Description");

	/** Columns shown for the GKE cluster listing. */
	private static final List<String> CLUSTER_COLUMNS = List.of("Name", "Location", "Status", "Version", "Nodes");

	/** Columns shown for the GKE workload listing. */
	private static final List<String> WORKLOAD_COLUMNS = List.of("Name", "Type", "Namespace", "Cluster", "Ready");

	/** Columns shown for the Pub/Sub topic listing. */
	private static final List<String> TOPIC_COLUMNS = List.of("Name", "Retention");

	/** Columns shown for the Pub/Sub subscription listing. */
	private static final List<String> SUBSCRIPTION_COLUMNS = List.of("Name", "Topic", "Type", "Ack deadline");

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

	/**
	 * {@code act} action dispatched by the host for the F3 "View Resources" function key on the
	 * project list. Opens the Cloud Resource Manager in the default browser.
	 */
	private static final String ACTION_VIEW_RESOURCES = "gcp.view.resources";

	/** Cloud Resource Manager URL opened by {@value #ACTION_VIEW_RESOURCES}. */
	private static final String RESOURCE_MANAGER_URL = "https://console.cloud.google.com/cloud-resource-manager";

	/**
	 * {@code act} action dispatched by the host for the Shift+F4 "Create Project" function key on
	 * the project list. Opens the Cloud Console project-creation page in the default browser.
	 */
	private static final String ACTION_CREATE_PROJECT = "gcp.create.project";

	/** Project-creation URL opened by {@value #ACTION_CREATE_PROJECT}. */
	private static final String PROJECT_CREATE_URL = "https://console.cloud.google.com/projectcreate";

	/**
	 * {@code act} action dispatched by the host for the Shift+F4 "Create bucket" function key on the
	 * bucket list. Opens the Cloud Console bucket-creation page (scoped to the current project) in
	 * the default browser.
	 */
	private static final String ACTION_CREATE_BUCKET = "gcp.create.bucket";

	/**
	 * {@code act} action dispatched by the host for the Shift+F4 "Create secret" function key on the
	 * secret list. Opens the Cloud Console secret-creation page (scoped to the current project).
	 */
	private static final String ACTION_CREATE_SECRET = "gcp.create.secret";

	/**
	 * {@code act} action dispatched by the host for the Shift+F4 "Create topic" function key on the
	 * Pub/Sub topics list. Opens the Cloud Console topic-creation page (scoped to the current project).
	 */
	private static final String ACTION_CREATE_TOPIC = "gcp.create.topic";

	private final String uuid = UUID.randomUUID().toString();
	private final GcpProjectRepository repository = new GcpProjectRepository();
	private final GcsBucketRepository bucketRepository = new GcsBucketRepository();
	private final GcpSecretRepository secretRepository = new GcpSecretRepository();
	private final GcpPubsubRepository pubsubRepository = new GcpPubsubRepository();
	private final GkeClusterRepository gkeClusterRepository = new GkeClusterRepository();
	private final GkeWorkloadRepository gkeWorkloadRepository = new GkeWorkloadRepository();

	private NuclrPluginContext context;
	private boolean focused;
	private NuclrResource currentResource;

	// In-memory hot layer over the disk cache so re-entering a listing (e.g. ".." back from a
	// project, or switching between GCS and Pub/Sub) does not touch disk on every navigation.
	// Populated from disk / gcloud on first read; dropped for the open level on "refresh.panel".
	private volatile List<GcpProject> cachedProjects;

	/** Per-project bucket cache, keyed by project id. */
	private final Map<String, List<GcsBucket>> bucketCache = new ConcurrentHashMap<>();

	/** Per-project GKE cluster cache, keyed by project id. */
	private final Map<String, List<GkeCluster>> clusterCache = new ConcurrentHashMap<>();

	/** Per-project GKE workload cache, keyed by project id. */
	private final Map<String, List<GkeWorkload>> workloadCache = new ConcurrentHashMap<>();

	// Active object listing: a live, lazily-consumed gcloud stream plus the rows shown so far.
	// One live stream is active at a time; completed rows stay available for duplicate checks.
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
	public String uuid() {
		return uuid;
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
		clusterCache.clear();
		workloadCache.clear();
		closePager();
		clearObjectListing();
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
	 * Bottom-bar function keys, tailored to the level currently open — the copy / make-folder /
	 * delete / find actions only make sense on Cloud Storage objects, so they are offered only
	 * inside a bucket (or a sub-folder). Other levels (projects, services, Pub/Sub topics and
	 * subscriptions, secrets) contribute no function keys. A search-results panel holds object
	 * hits, so it offers Copy only.
	 *
	 * <p>Keyed off the plugin's {@link #currentResource} (the open listing), not {@code resource}
	 * (the cursor row), so the bar reflects where you are rather than what is highlighted.
	 */
	@Override
	public List<NuclrMenuResource> menuItems(NuclrResource resource) {
		// Every GCP view is a listing of named resources (the "Name" column is present in all of them),
		// so offer name sorting everywhere via the host's plugin-declared sort mechanism. Size/date
		// sorts are intentionally not offered: GCP resources carry no real length or timestamps
		// (dates are stubbed to EPOCH), so those criteria would sort nothing.
		List<NuclrMenuResource> items = new ArrayList<>(viewMenuItems());
		items.add(new NuclrMenuResource("Name", "Ctrl+F3", "filepanel.sort:name:Name"));
		items.add(new NuclrMenuResource("Sort", "Ctrl+F12", "filepanel.sort:dialog"));
		return items;
	}

	/** The view-specific (non-sort) function-bar entries for the currently open GCP resource. */
	private List<NuclrMenuResource> viewMenuItems() {
		if (GcpResource.isRoot(currentResource)) {
			return List.of(
					new NuclrMenuResource("View Resources", "F3", ACTION_VIEW_RESOURCES),
					new NuclrMenuResource("Create Project", "Shift+F4", ACTION_CREATE_PROJECT));
		}
		if (GcpResource.isService(currentResource)) {
			String serviceType = GcpResource.serviceType(currentResource);
			if (GcpResource.SERVICE_GCS.equals(serviceType)) {
				return List.of(new NuclrMenuResource("Create bucket", "Shift+F4", ACTION_CREATE_BUCKET));
			}
			if (GcpResource.SERVICE_SECRET.equals(serviceType)) {
				return List.of(new NuclrMenuResource("Create secret", "Shift+F4", ACTION_CREATE_SECRET));
			}
			return List.of();
		}
		if (GcpResource.isBucket(currentResource) || GcpResource.isObjectDir(currentResource)) {
			return List.of(
					new NuclrMenuResource("Copy", "F5", ACTION_COPY),
					new NuclrMenuResource("Make Folder", "F7", ACTION_MAKE_FOLDER),
					new NuclrMenuResource("Delete", "F8", ACTION_DELETE),
					new NuclrMenuResource("Find", "Alt+F7", ACTION_FIND));
		}
		if (GcpResource.isPubsubCategory(currentResource)
				&& GcpResource.PUBSUB_TOPICS.equals(GcpResource.pubsubCategory(currentResource))) {
			return List.of(new NuclrMenuResource("Create topic", "Shift+F4", ACTION_CREATE_TOPIC));
		}
		if (GcpResource.isSearchResults(currentResource)) {
			return List.of(new NuclrMenuResource("Copy", "F5", ACTION_COPY));
		}
		return List.of();
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
			if (!GcpResource.isBucket(resourceToOpen) && !GcpResource.isObjectDir(resourceToOpen)) {
				clearObjectListing();
			}
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
			if (GcpResource.SERVICE_COMPUTE.equals(serviceType)) {
				this.currentResource = GcpResource.computeEngineService(projectId);
				return listComputeEngine(projectId, sink);
			}
			if (GcpResource.SERVICE_GKE.equals(serviceType)) {
				this.currentResource = GcpResource.gkeService(projectId);
				return listGke(projectId, sink);
			}
			// Any other (future) service is not browsable yet; show only the "..".
			this.currentResource = GcpResource.pubsubService(projectId);
			return serviceStub(projectId, sink);
		}

		if (GcpResource.isPubsubCategory(resourceToOpen)) {
			String projectId = GcpResource.projectId(resourceToOpen);
			if (GcpResource.PUBSUB_SUBSCRIPTIONS.equals(GcpResource.pubsubCategory(resourceToOpen))) {
				this.currentResource = GcpResource.pubsubSubscriptions(projectId);
				return listSubscriptions(projectId, cancelled, sink);
			}
			this.currentResource = GcpResource.pubsubTopics(projectId);
			return listTopics(projectId, cancelled, sink);
		}

		if (GcpResource.isComputeCategory(resourceToOpen)) {
			// Rebuild a clean category node (the incoming resource may be the ".." back from here) so
			// the location bar shows the category name rather than "..".
			String projectId = GcpResource.projectId(resourceToOpen);
			String category = GcpResource.computeCategory(resourceToOpen);
			if (GcpResource.COMPUTE_STORAGE.equals(category)) {
				this.currentResource = GcpResource.computeStorage(projectId);
				return listComputeStorage(projectId, sink);
			}
			if (GcpResource.COMPUTE_INSTANCE_GROUPS.equals(category)) {
				this.currentResource = GcpResource.computeInstanceGroups(projectId);
				return listComputeInstanceGroups(projectId, sink);
			}
			if (GcpResource.COMPUTE_EXTENSION_MANAGER.equals(category)) {
				this.currentResource = GcpResource.computeExtensionManager(projectId);
				return listComputeExtensionManager(projectId, sink);
			}
			if (GcpResource.COMPUTE_VM_MANAGER.equals(category)) {
				this.currentResource = GcpResource.computeVmManager(projectId);
				return listComputeVmManager(projectId, sink);
			}
			if (GcpResource.COMPUTE_BARE_METAL.equals(category)) {
				this.currentResource = GcpResource.computeBareMetalSolution(projectId);
				return listComputeBareMetalSolution(projectId, sink);
			}
			if (GcpResource.COMPUTE_SETTINGS.equals(category)) {
				this.currentResource = GcpResource.computeSettings(projectId);
				return listComputeSettings(projectId, sink);
			}
			this.currentResource = GcpResource.computeVirtualMachines(projectId);
			return listComputeVirtualMachines(projectId, sink);
		}

		if (GcpResource.isGkeCategory(resourceToOpen)) {
			// Rebuild a clean category node (the incoming resource may be the ".." back from here) so
			// the location bar shows the category name rather than "..".
			String projectId = GcpResource.projectId(resourceToOpen);
			String category = GcpResource.gkeCategory(resourceToOpen);
			if (GcpResource.GKE_CLUSTERS.equals(category)) {
				this.currentResource = GcpResource.gkeClusters(projectId);
				return listGkeClusters(projectId, cancelled, sink);
			}
			if (GcpResource.GKE_WORKLOADS.equals(category)) {
				this.currentResource = GcpResource.gkeWorkloads(projectId);
				return listGkeWorkloads(projectId, cancelled, sink);
			}
			this.currentResource = GcpResource.gkeResourcesManagement(projectId);
			return listGkeResourcesManagement(projectId, sink);
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

	/** A project lists the GCP services it exposes ({@code ..}, GCS, Pub/Sub, Compute Engine, Secret Manager). */
	private NuclrResourceData listServices(String projectId, EntrySink sink) {

		var data = new NuclrResourceData();
		data.setColumnNames(SERVICE_COLUMNS);
		if (sink != null) {
			sink.columns(SERVICE_COLUMNS);
		}

		add(data, sink, GcpResource.parent()); // ".." back to the project list
		add(data, sink, GcpResource.gcsService(projectId));
		add(data, sink, GcpResource.pubsubService(projectId));
		add(data, sink, GcpResource.computeEngineService(projectId));
		add(data, sink, GcpResource.gkeService(projectId));
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

	/** The Compute Engine service lists its sections ({@code ..} then each section). */
	private NuclrResourceData listComputeEngine(String projectId, EntrySink sink) {

		var data = new NuclrResourceData();
		data.setColumnNames(SERVICE_COLUMNS);
		if (sink != null) {
			sink.columns(SERVICE_COLUMNS);
		}

		add(data, sink, GcpResource.parentToProject(projectId)); // ".." back to the service list
		add(data, sink, GcpResource.computeVirtualMachines(projectId));
		add(data, sink, GcpResource.computeStorage(projectId));
		add(data, sink, GcpResource.computeInstanceGroups(projectId));
		add(data, sink, GcpResource.computeExtensionManager(projectId));
		add(data, sink, GcpResource.computeVmManager(projectId));
		add(data, sink, GcpResource.computeBareMetalSolution(projectId));
		add(data, sink, GcpResource.computeSettings(projectId));
		return data;
	}

	/** The Virtual Machines category lists its sections ({@code ..} then each), each opening a Cloud Console page. */
	private NuclrResourceData listComputeVirtualMachines(String projectId, EntrySink sink) {

		var data = new NuclrResourceData();
		data.setColumnNames(SERVICE_COLUMNS);
		if (sink != null) {
			sink.columns(SERVICE_COLUMNS);
		}

		add(data, sink, GcpResource.parentToCompute(projectId)); // ".." back to Compute Engine
		add(data, sink, GcpResource.consoleLink(projectId, "VM Instances", "Virtual machine instances", "compute/instances"));
		add(data, sink, GcpResource.consoleLink(projectId, "Instance templates", "Reusable VM configurations", "compute/instanceTemplates/list"));
		add(data, sink, GcpResource.consoleLink(projectId, "Sole-tenant nodes", "Dedicated host hardware", "compute/soleTenancy"));
		add(data, sink, GcpResource.consoleLink(projectId, "Machine images", "Full VM backups", "compute/machineImages"));
		add(data, sink, GcpResource.consoleLink(projectId, "TPUs", "Tensor Processing Units", "compute/tpus"));
		add(data, sink, GcpResource.consoleLink(projectId, "Committed-use discounts", "Committed-use contracts", "compute/commitments"));
		add(data, sink, GcpResource.consoleLink(projectId, "Reservations", "Reserved VM capacity", "compute/reservations"));
		add(data, sink, GcpResource.consoleLink(projectId, "Capacity advisor", "Capacity recommendations", "compute/capacityAdvisor"));
		return data;
	}

	/** The Storage category lists its sections ({@code ..} then each), each opening a Cloud Console page. */
	private NuclrResourceData listComputeStorage(String projectId, EntrySink sink) {

		var data = new NuclrResourceData();
		data.setColumnNames(SERVICE_COLUMNS);
		if (sink != null) {
			sink.columns(SERVICE_COLUMNS);
		}

		add(data, sink, GcpResource.parentToCompute(projectId)); // ".." back to Compute Engine
		add(data, sink, GcpResource.consoleLink(projectId, "Disks", "Persistent and boot disks", "compute/disks"));
		add(data, sink, GcpResource.consoleLink(projectId, "Storage pools", "Pooled block storage", "compute/storagePools"));
		add(data, sink, GcpResource.consoleLink(projectId, "Snapshots", "Disk snapshots", "compute/snapshots"));
		add(data, sink, GcpResource.consoleLink(projectId, "Images", "Custom and public images", "compute/images?tab=images"));
		add(data, sink, GcpResource.consoleLink(projectId, "Async replication", "Cross-region disk replication", "compute/asynchronousReplication"));
		add(data, sink, GcpResource.consoleLink(projectId, "Consistency groups", "Replication consistency groups", "compute/consistencyGroups"));
		return data;
	}

	/** The Instance Groups category lists its sections ({@code ..} then each), each opening a Cloud Console page. */
	private NuclrResourceData listComputeInstanceGroups(String projectId, EntrySink sink) {

		var data = new NuclrResourceData();
		data.setColumnNames(SERVICE_COLUMNS);
		if (sink != null) {
			sink.columns(SERVICE_COLUMNS);
		}

		add(data, sink, GcpResource.parentToCompute(projectId)); // ".." back to Compute Engine
		add(data, sink, GcpResource.consoleLink(projectId, "Instant groups", "Managed and unmanaged instance groups", "compute/instanceGroups/list"));
		add(data, sink, GcpResource.consoleLink(projectId, "Health checks", "Instance health checks", "compute/healthChecks"));
		return data;
	}

	/** The VM Extension Manager category lists its sections ({@code ..} then each), each opening a Cloud Console page. */
	private NuclrResourceData listComputeExtensionManager(String projectId, EntrySink sink) {

		var data = new NuclrResourceData();
		data.setColumnNames(SERVICE_COLUMNS);
		if (sink != null) {
			sink.columns(SERVICE_COLUMNS);
		}

		add(data, sink, GcpResource.parentToCompute(projectId)); // ".." back to Compute Engine
		add(data, sink, GcpResource.consoleLink(projectId, "Extension policies", "VM extension policies", "compute/extensionManager/policies/global"));
		return data;
	}

	/** The VM Manager category lists its sections ({@code ..} then each), each opening a Cloud Console page. */
	private NuclrResourceData listComputeVmManager(String projectId, EntrySink sink) {

		var data = new NuclrResourceData();
		data.setColumnNames(SERVICE_COLUMNS);
		if (sink != null) {
			sink.columns(SERVICE_COLUMNS);
		}

		add(data, sink, GcpResource.parentToCompute(projectId)); // ".." back to Compute Engine
		add(data, sink, GcpResource.consoleLink(projectId, "Patch", "OS patch management", "compute/patch"));
		add(data, sink, GcpResource.consoleLink(projectId, "OS policies", "OS configuration policies", "compute/config/projects"));
		return data;
	}

	/** The Bare Metal Solution category lists its sections ({@code ..} then each), each opening a Cloud Console page. */
	private NuclrResourceData listComputeBareMetalSolution(String projectId, EntrySink sink) {

		var data = new NuclrResourceData();
		data.setColumnNames(SERVICE_COLUMNS);
		if (sink != null) {
			sink.columns(SERVICE_COLUMNS);
		}

		add(data, sink, GcpResource.parentToCompute(projectId)); // ".." back to Compute Engine
		add(data, sink, GcpResource.consoleLink(projectId, "Servers", "Bare metal servers", "compute/bareMetalSolution/servers"));
		add(data, sink, GcpResource.consoleLink(projectId, "Networks", "Bare metal networks", "compute/bareMetalSolution/networks"));
		add(data, sink, GcpResource.consoleLink(projectId, "VRFs", "Virtual routing and forwarding", "compute/bareMetalSolution/vrfs"));
		add(data, sink, GcpResource.consoleLink(projectId, "Volumes", "Storage volumes", "compute/bareMetalSolution/volumes"));
		add(data, sink, GcpResource.consoleLink(projectId, "NFS shares", "NFS file shares", "compute/bareMetalSolution/nfsShares"));
		add(data, sink, GcpResource.consoleLink(projectId, "Procurements", "Resource procurements", "compute/bareMetalSolution/procurements"));
		add(data, sink, GcpResource.consoleLink(projectId, "Maintenance events", "Scheduled maintenance events", "compute/bareMetalSolution/maintenanceEvents"));
		return data;
	}

	/** The Settings category lists its sections ({@code ..} then each), each opening a Cloud Console page. */
	private NuclrResourceData listComputeSettings(String projectId, EntrySink sink) {

		var data = new NuclrResourceData();
		data.setColumnNames(SERVICE_COLUMNS);
		if (sink != null) {
			sink.columns(SERVICE_COLUMNS);
		}

		add(data, sink, GcpResource.parentToCompute(projectId)); // ".." back to Compute Engine
		add(data, sink, GcpResource.consoleLink(projectId, "Rollouts", "Configuration rollouts", "compute/rollouts"));
		add(data, sink, GcpResource.consoleLink(projectId, "Metadata", "Project metadata", "compute/metadata"));
		add(data, sink, GcpResource.consoleLink(projectId, "Zones", "Compute zones", "compute/zones"));
		add(data, sink, GcpResource.consoleLink(projectId, "Network endpoint groups", "Network endpoint groups", "compute/networkendpointgroups/list"));
		add(data, sink, GcpResource.consoleLink(projectId, "Preview features", "Preview features", "compute/previewFeatures"));
		add(data, sink, GcpResource.consoleLink(projectId, "Operations", "Compute operations", "compute/operations"));
		add(data, sink, GcpResource.consoleLink(projectId, "Settings", "Compute Engine settings", "compute/settings"));
		return data;
	}

	/** The GKE service lists its sections ({@code ..} then each section). */
	private NuclrResourceData listGke(String projectId, EntrySink sink) {

		var data = new NuclrResourceData();
		data.setColumnNames(SERVICE_COLUMNS);
		if (sink != null) {
			sink.columns(SERVICE_COLUMNS);
		}

		add(data, sink, GcpResource.parentToProject(projectId)); // ".." back to the service list
		add(data, sink, GcpResource.gkeResourcesManagement(projectId));
		add(data, sink, GcpResource.gkeCategory(projectId, null, "Posture management", "Security posture and findings"));
		add(data, sink, GcpResource.gkeCategory(projectId, null, "Networking", "Gateways, services, and ingress"));
		add(data, sink, GcpResource.gkeCategory(projectId, null, "Features", "GKE features"));
		return data;
	}

	/**
	 * GKE Resources management lists its sections ({@code ..} then each). Clusters and Workloads are
	 * browsable folders (live-fetched on entry); the rest open a Cloud Console page.
	 */
	private NuclrResourceData listGkeResourcesManagement(String projectId, EntrySink sink) {

		var data = new NuclrResourceData();
		data.setColumnNames(SERVICE_COLUMNS);
		if (sink != null) {
			sink.columns(SERVICE_COLUMNS);
		}

		add(data, sink, GcpResource.parentToGke(projectId)); // ".." back to GKE
		add(data, sink, GcpResource.consoleLink(projectId, "Overview", "Clusters overview", "kubernetes/list/overview"));
		add(data, sink, GcpResource.gkeClusters(projectId)); // browsable: live cluster list
		add(data, sink, GcpResource.gkeWorkloads(projectId)); // browsable: live workload list
		add(data, sink, GcpResource.consoleLink(projectId, "AI/ML", "AI/ML on GKE", "kubernetes/aiml/overview"));
		add(data, sink, GcpResource.consoleLink(projectId, "Teams", "GKE teams", "kubernetes/teams"));
		add(data, sink, GcpResource.consoleLink(projectId, "Applications", "Deployed applications", "kubernetes/application"));
		add(data, sink, GcpResource.consoleLink(projectId, "Secrets and ConfigMaps", "Secrets and ConfigMaps", "kubernetes/config"));
		add(data, sink, GcpResource.consoleLink(projectId, "Storage", "Persistent volume claims", "kubernetes/persistentvolumeclaim"));
		add(data, sink, GcpResource.consoleLink(projectId, "Object browser", "Kubernetes object browser", "kubernetes/object/browser"));
		add(data, sink, GcpResource.consoleLink(projectId, "Upgrades", "Cluster upgrades", "kubernetes/upgrades"));
		add(data, sink, GcpResource.consoleLink(projectId, "Backup for GKE", "Backups for GKE", "kubernetes/backups"));
		return data;
	}

	/** The Clusters category lists the project's GKE clusters ({@code ..} then each), each opening its overview page. */
	private NuclrResourceData listGkeClusters(String projectId, AtomicBoolean cancelled, EntrySink sink) {

		var data = new NuclrResourceData();
		data.setColumnNames(CLUSTER_COLUMNS);
		if (sink != null) {
			sink.columns(CLUSTER_COLUMNS);
		}

		add(data, sink, GcpResource.parentToGkeResources(projectId)); // ".." back to Resources management

		List<GkeCluster> clusters = clusters(projectId);
		if (clusters == null) {
			// Hard error already surfaced via GcpErrorDialog; show just the "..".
			return data;
		}

		for (GkeCluster cluster : clusters) {
			if (cancelled != null && cancelled.get()) {
				break;
			}
			add(data, sink, GcpResource.gkeCluster(projectId, cluster));
		}
		log.info("GKE cluster listing for {}: {} cluster(s)", projectId, clusters.size());
		return data;
	}

	/**
	 * Return a project's GKE clusters. Served from the in-memory hot layer, then the restart-persistent
	 * {@link GcpDiskCache}, and only then from a live {@code gcloud} run (whose result is persisted).
	 * Returns {@code null} on a hard error (already surfaced via {@link GcpErrorDialog}); "no clusters"
	 * is an empty list.
	 */
	private List<GkeCluster> clusters(String projectId) {

		List<GkeCluster> cached = clusterCache.get(projectId);
		if (cached != null) {
			return cached;
		}

		List<GkeCluster> disk = GcpDiskCache.loadClusters(projectId);
		if (disk != null) {
			clusterCache.put(projectId, disk);
			return disk;
		}

		GkeClusterRepository.Result result = gkeClusterRepository.listClusters(projectId);
		return switch (result) {
			case GkeClusterRepository.Result.Ok ok -> {
				GcpDiskCache.saveClusters(projectId, ok.clusters());
				clusterCache.put(projectId, ok.clusters());
				yield ok.clusters();
			}
			case GkeClusterRepository.Result.Err err -> {
				log.warn("GKE cluster list failed for {}: {}", projectId, err.error());
				GcpErrorDialog.show(err.error());
				yield null;
			}
		};
	}

	/** The Workloads category lists the project's workloads ({@code ..} then each), each opening its overview page. */
	private NuclrResourceData listGkeWorkloads(String projectId, AtomicBoolean cancelled, EntrySink sink) {

		var data = new NuclrResourceData();
		data.setColumnNames(WORKLOAD_COLUMNS);
		if (sink != null) {
			sink.columns(WORKLOAD_COLUMNS);
		}

		add(data, sink, GcpResource.parentToGkeResources(projectId)); // ".." back to Resources management

		List<GkeWorkload> workloads = workloads(projectId);
		if (workloads == null) {
			// Hard error already surfaced via GcpErrorDialog; show just the "..".
			return data;
		}

		for (GkeWorkload workload : workloads) {
			if (cancelled != null && cancelled.get()) {
				break;
			}
			add(data, sink, GcpResource.gkeWorkload(projectId, workload));
		}
		log.info("GKE workload listing for {}: {} workload(s)", projectId, workloads.size());
		return data;
	}

	/**
	 * Return a project's GKE workloads. Served from the in-memory hot layer, then the restart-persistent
	 * {@link GcpDiskCache}, and only then from a live run (list clusters, then kubectl per cluster) whose
	 * result is persisted. Returns {@code null} on a hard error (already surfaced via {@link GcpErrorDialog});
	 * "no workloads" is an empty list.
	 */
	private List<GkeWorkload> workloads(String projectId) {

		List<GkeWorkload> cached = workloadCache.get(projectId);
		if (cached != null) {
			return cached;
		}

		List<GkeWorkload> disk = GcpDiskCache.loadWorkloads(projectId);
		if (disk != null) {
			workloadCache.put(projectId, disk);
			return disk;
		}

		// Workloads live under clusters, so the workload fetch needs the (cached) cluster list first.
		List<GkeCluster> clusters = clusters(projectId);
		if (clusters == null) {
			return null; // hard error already surfaced by clusters()
		}

		GkeWorkloadRepository.Result result = gkeWorkloadRepository.listWorkloads(projectId, clusters);
		return switch (result) {
			case GkeWorkloadRepository.Result.Ok ok -> {
				GcpDiskCache.saveWorkloads(projectId, ok.workloads());
				workloadCache.put(projectId, ok.workloads());
				yield ok.workloads();
			}
			case GkeWorkloadRepository.Result.Err err -> {
				log.warn("GKE workload list failed for {}: {}", projectId, err.error());
				GcpErrorDialog.show(err.error());
				yield null;
			}
		};
	}

	/** The Topics category lists the project's Pub/Sub topics ({@code ..} then each topic). */
	private NuclrResourceData listTopics(String projectId, AtomicBoolean cancelled, EntrySink sink) {

		var data = new NuclrResourceData();
		data.setColumnNames(TOPIC_COLUMNS);
		if (sink != null) {
			sink.columns(TOPIC_COLUMNS);
		}

		add(data, sink, GcpResource.parentToPubsub(projectId)); // ".." back to Pub/Sub

		GcpPubsubRepository.TopicResult result = pubsubRepository.listTopics(projectId);
		switch (result) {
			case GcpPubsubRepository.TopicResult.Ok ok -> {
				for (GcpPubsubTopic topic : ok.topics()) {
					if (cancelled != null && cancelled.get()) {
						break;
					}
					add(data, sink, GcpResource.pubsubTopic(projectId, topic));
				}
				log.info("Pub/Sub topic listing for {}: {} topic(s)", projectId, ok.topics().size());
			}
			case GcpPubsubRepository.TopicResult.Err err -> {
				log.warn("Pub/Sub topic list failed for {}: {}", projectId, err.error());
				GcpErrorDialog.show(err.error());
			}
		}
		return data;
	}

	/** The Subscriptions category lists the project's Pub/Sub subscriptions ({@code ..} then each). */
	private NuclrResourceData listSubscriptions(String projectId, AtomicBoolean cancelled, EntrySink sink) {

		var data = new NuclrResourceData();
		data.setColumnNames(SUBSCRIPTION_COLUMNS);
		if (sink != null) {
			sink.columns(SUBSCRIPTION_COLUMNS);
		}

		add(data, sink, GcpResource.parentToPubsub(projectId)); // ".." back to Pub/Sub

		GcpPubsubRepository.SubscriptionResult result = pubsubRepository.listSubscriptions(projectId);
		switch (result) {
			case GcpPubsubRepository.SubscriptionResult.Ok ok -> {
				for (GcpPubsubSubscription subscription : ok.subscriptions()) {
					if (cancelled != null && cancelled.get()) {
						break;
					}
					add(data, sink, GcpResource.pubsubSubscription(projectId, subscription));
				}
				log.info("Pub/Sub subscription listing for {}: {} subscription(s)", projectId, ok.subscriptions().size());
			}
			case GcpPubsubRepository.SubscriptionResult.Err err -> {
				log.warn("Pub/Sub subscription list failed for {}: {}", projectId, err.error());
				GcpErrorDialog.show(err.error());
			}
		}
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
		clearObjectListing();
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
			closePager(); // keep rows for upload conflict checks
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

	/** Closes the live object stream (if any), keeping the rendered rows for current-listing actions. */
	private void closePager() {
		if (pager != null) {
			pager.close();
			pager = null;
		}
		pagerKey = null;
	}

	/** Forget the rows/models from the previous object listing. */
	private void clearObjectListing() {
		pagerRows.clear();
		pagerObjects.clear();
	}

	private static String pagerKey(String bucket, String prefix) {
		return bucket.length() + ":" + bucket + prefix;
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
	 * the GCS service, that {@code (bucket, prefix)}'s objects inside a bucket, or that project's GKE
	 * clusters or workloads in the Clusters / Workloads listings — so the host's follow-up reload
	 * re-queries just what the user is looking at. Other levels keep their persistent cache. Other
	 * actions are ignored.
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
			log.info("GCP accept-copy action: current={}, selected={}, focused={}",
					currentResource, selectedResources == null ? 0 : selectedResources.size(), focusedResource);
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

		if (ACTION_VIEW_RESOURCES.equals(actionType)) {
			browse(RESOURCE_MANAGER_URL);
			return;
		}

		if (ACTION_CREATE_PROJECT.equals(actionType)) {
			browse(PROJECT_CREATE_URL);
			return;
		}

		if (ACTION_CREATE_BUCKET.equals(actionType)) {
			browse(withProject("https://console.cloud.google.com/storage/create-bucket"));
			return;
		}

		if (ACTION_CREATE_SECRET.equals(actionType)) {
			browse(withProject("https://console.cloud.google.com/security/secret-manager/create"));
			return;
		}

		if (ACTION_CREATE_TOPIC.equals(actionType)) {
			browse(withProject("https://console.cloud.google.com/cloudpubsub/topic/create"));
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
			clearObjectListing();
			log.info("GCP object listing gs://{}/{} invalidated on '{}'", bucket, prefix, actionType);
		} else if (GcpResource.isService(currentResource)
				&& GcpResource.SERVICE_GCS.equals(GcpResource.serviceType(currentResource))) {
			String projectId = GcpResource.projectId(currentResource);
			bucketCache.remove(projectId);
			GcpDiskCache.clearBuckets(projectId);
			log.info("GCS bucket listing for {} invalidated on '{}'", projectId, actionType);
		} else if (GcpResource.isGkeCategory(currentResource)
				&& GcpResource.GKE_CLUSTERS.equals(GcpResource.gkeCategory(currentResource))) {
			String projectId = GcpResource.projectId(currentResource);
			clusterCache.remove(projectId);
			GcpDiskCache.clearClusters(projectId);
			log.info("GKE cluster listing for {} invalidated on '{}'", projectId, actionType);
		} else if (GcpResource.isGkeCategory(currentResource)
				&& GcpResource.GKE_WORKLOADS.equals(GcpResource.gkeCategory(currentResource))) {
			String projectId = GcpResource.projectId(currentResource);
			workloadCache.remove(projectId);
			GcpDiskCache.clearWorkloads(projectId);
			// Workloads are derived from the cluster list (one get-credentials per cluster), so a stale
			// cluster list breaks the re-fetch: a cluster that has since been deleted makes
			// get-credentials 404 and every cluster fail, leaving the panel blank instead of reloading.
			// Drop the cached clusters too so the reload rebuilds them live and reflects reality.
			clusterCache.remove(projectId);
			GcpDiskCache.clearClusters(projectId);
			log.info("GKE workload listing for {} invalidated on '{}' (cluster list dropped too)", projectId, actionType);
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
		browse(GcpResource.consoleUrl(resource));
	}

	/** Append {@code ?project=<current project id>} to a Console base URL (or leave it bare if unknown). */
	private String withProject(String baseUrl) {
		String projectId = GcpResource.projectId(currentResource);
		if (projectId == null || projectId.isBlank()) {
			return baseUrl;
		}
		return baseUrl + "?project=" + URLEncoder.encode(projectId, StandardCharsets.UTF_8);
	}

	/** Open {@code url} in the default browser, off the EDT. No-op if {@code url} is null or browsing is unsupported. */
	private static void browse(String url) {
		if (url == null) {
			return;
		}
		Thread.ofVirtual().start(() -> {
			try {
				if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
					Desktop.getDesktop().browse(URI.create(url));
					log.info("Opened in Cloud Console: {}", url);
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
		if (GcpResource.isComputeCategory(currentResource)) {
			return "GCP: " + GcpResource.projectId(currentResource) + " / Compute Engine / " + currentResource.getName();
		}
		if (GcpResource.isGkeCategory(currentResource)) {
			return "GCP: " + GcpResource.projectId(currentResource) + " / GKE / " + currentResource.getName();
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
