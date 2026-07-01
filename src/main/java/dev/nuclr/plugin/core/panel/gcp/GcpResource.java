package dev.nuclr.plugin.core.panel.gcp;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import dev.nuclr.platform.plugin.NuclrResource;
import lombok.extern.slf4j.Slf4j;

/**
 * Virtual, path-less resource for the GCP panel.
 *
 * <p>There are two kinds:
 * <ul>
 *   <li>the single <b>root</b> — the "GCP" mount shown in the Alt+F1/Alt+F2 drive
 *       selector; opening it lists the accessible gcloud projects, and</li>
 *   <li>one <b>project</b> entry per accessible project.</li>
 * </ul>
 *
 * <p>Both carry {@link #MARKER} in their metadata so {@link GcpFilePanelProvider#supports}
 * can claim them, and both have a {@code null} {@link #getPath() path} so the local
 * filesystem plugin never tries to handle them. Following the {@code TempFilePanelPlugin}
 * pattern, the resource is purely in-memory; the table renders each column from the
 * resource {@code metadata}.
 */
@Slf4j
public final class GcpResource extends NuclrResource {

	private static final long serialVersionUID = 1L;

	/** Metadata flag marking a resource as belonging to the GCP panel. */
	static final String MARKER = "nuclr.gcp.panel";

	/** Metadata key distinguishing the kind of resource (root / project / service / bucket). */
	static final String KIND = "nuclr.gcp.kind";

	static final String KIND_ROOT = "root";
	static final String KIND_PROJECT = "project";
	static final String KIND_SERVICE = "service";
	static final String KIND_BUCKET = "bucket";
	static final String KIND_OBJECT_DIR = "object-dir";
	static final String KIND_OBJECT = "object";
	static final String KIND_LOAD_MORE = "load-more";

	/** Metadata carrying the owning project id (on project, service, bucket, and object resources). */
	static final String PROJECT_ID = "nuclr.gcp.projectId";

	/** Metadata key identifying which service a {@link #KIND_SERVICE} resource represents. */
	static final String SERVICE = "nuclr.gcp.service";

	static final String SERVICE_GCS = "gcs";
	static final String SERVICE_PUBSUB = "pubsub";

	/** Metadata: bucket name and object-key prefix on bucket / object-dir / load-more resources. */
	static final String BUCKET = "nuclr.gcp.bucket";
	static final String PREFIX = "nuclr.gcp.prefix";

	/** Metadata: full object key (prefix + name) on object resources, for self-download. */
	static final String OBJECT_KEY = "nuclr.gcp.objectKey";

	/** Stable identifier of the single GCP root mount. */
	static final String ROOT_UUID = "gcp://";

	private static final LocalDateTime EPOCH = LocalDateTime.ofEpochSecond(0, 0, ZoneOffset.UTC);

	private GcpResource() {
		super(null);
		getMetadata().put(MARKER, Boolean.TRUE);
		// Non-null timestamps so the panel's date-based sort comparators never NPE on
		// these virtual (path-less) entries.
		setCreatedDateTime(EPOCH);
		setLastModifiedDateTime(EPOCH);
		setLastAccessDateTime(EPOCH);
	}

	/** The single GCP root resource — the "GCP" mount that lists projects. */
	static GcpResource root() {
		GcpResource r = new GcpResource();
		r.setUuid(ROOT_UUID);
		r.setFullPath(ROOT_UUID);
		r.setFolder(true);
		r.getMetadata().put(KIND, KIND_ROOT);
		r.rename("Google Cloud");
		return r;
	}

	/** The synthetic ".." entry that navigates from a project back to the root. */
	static GcpResource parent() {
		GcpResource r = root();
		r.rename("..");
		return r;
	}

	/** A project entry shown under the root. */
	static GcpResource project(GcpProject project) {
		GcpResource r = projectRef(project.projectId());
		r.getMetadata().put("Project Name", blankToDash(project.name()));
		r.getMetadata().put("Number", project.projectNumber() > 0 ? String.valueOf(project.projectNumber()) : "-");
		r.getMetadata().put("State", blankToDash(project.lifecycleState()));
		return r;
	}

	/**
	 * A bare project resource identified only by its id — used as the current location and
	 * as the ".." target when navigating back to a project's service list (the rich columns
	 * from {@link #project(GcpProject)} are only needed in the root listing).
	 */
	static GcpResource projectRef(String projectId) {
		GcpResource r = new GcpResource();
		r.setUuid(ROOT_UUID + "project/" + projectId);
		r.setFullPath(ROOT_UUID + "project/" + projectId);
		r.setFolder(true);
		r.getMetadata().put(KIND, KIND_PROJECT);
		r.getMetadata().put(PROJECT_ID, projectId);
		r.rename(projectId);
		return r;
	}

	/** The synthetic ".." entry that navigates from a service list back to its project's services. */
	static GcpResource parentToProject(String projectId) {
		GcpResource r = projectRef(projectId);
		r.rename("..");
		return r;
	}

	/** A service node (e.g. GCS, Pub/Sub) shown under a project. */
	private static GcpResource service(String projectId, String serviceType, String displayName, String description) {
		GcpResource r = new GcpResource();
		r.setUuid(ROOT_UUID + "project/" + projectId + "/" + serviceType);
		r.setFullPath(ROOT_UUID + "project/" + projectId + "/" + serviceType);
		r.setFolder(true);
		r.getMetadata().put(KIND, KIND_SERVICE);
		r.getMetadata().put(PROJECT_ID, projectId);
		r.getMetadata().put(SERVICE, serviceType);
		r.rename(displayName);
		r.getMetadata().put("Description", description);
		return r;
	}

	/** The Cloud Storage service entry under a project. */
	static GcpResource gcsService(String projectId) {
		return service(projectId, SERVICE_GCS, "GCS", "Cloud Storage buckets");
	}

	/** The Pub/Sub service entry under a project. */
	static GcpResource pubsubService(String projectId) {
		return service(projectId, SERVICE_PUBSUB, "Pub/Sub", "Topics and subscriptions");
	}

	/** A Cloud Storage bucket entry shown under a project's GCS service; navigable into its objects. */
	static GcpResource bucket(String projectId, GcsBucket bucket) {
		GcpResource r = new GcpResource();
		String name = bucket.name();
		r.setUuid(ROOT_UUID + "bucket/" + name);
		r.setFullPath(ROOT_UUID + "bucket/" + name);
		r.setFolder(true);
		r.getMetadata().put(KIND, KIND_BUCKET);
		r.getMetadata().put(PROJECT_ID, projectId);
		r.getMetadata().put(BUCKET, name);
		r.getMetadata().put(PREFIX, ""); // bucket root
		r.rename(name);
		r.getMetadata().put("Created", bucket.created());
		r.getMetadata().put("Location type", bucket.locationType());
		r.getMetadata().put("Location", bucket.location());
		r.getMetadata().put("Default storage class", bucket.defaultStorageClass());
		r.getMetadata().put("Last modified", bucket.lastModified());
		r.getMetadata().put("Public Access", bucket.publicAccess());
		return r;
	}

	/**
	 * A navigable "directory" inside a bucket, identified by an object-key {@code prefix}
	 * (ending in {@code /}). Used both for sub-folders and as the ".." / current-location
	 * reference for an object listing.
	 */
	static GcpResource objectDir(String projectId, String bucket, String prefix, String displayName) {
		GcpResource r = new GcpResource();
		r.setUuid(ROOT_UUID + "bucket/" + bucket + "/" + prefix);
		r.setFullPath(ROOT_UUID + "bucket/" + bucket + "/" + prefix);
		r.setFolder(true);
		r.getMetadata().put(KIND, KIND_OBJECT_DIR);
		r.getMetadata().put(PROJECT_ID, projectId);
		r.getMetadata().put(BUCKET, bucket);
		r.getMetadata().put(PREFIX, prefix);
		r.rename(displayName);
		r.getMetadata().put("Size", "-");
		r.getMetadata().put("Storage class", "-");
		r.getMetadata().put("Updated", "-");
		return r;
	}

	/** A leaf object entry within a bucket listing. {@code key} is the full object key (prefix + name). */
	static GcpResource object(String projectId, String bucket, String key, GcsObject object) {
		GcpResource r = new GcpResource();
		r.setUuid(ROOT_UUID + "object/" + bucket + "/" + key);
		r.setFullPath("gs://" + bucket + "/" + key);
		r.setFolder(false);
		r.getMetadata().put(KIND, KIND_OBJECT);
		r.getMetadata().put(PROJECT_ID, projectId);
		r.getMetadata().put(BUCKET, bucket);
		r.getMetadata().put(OBJECT_KEY, key);
		r.rename(object.name());
		r.getMetadata().put("Size", object.size());
		r.getMetadata().put("Storage class", object.storageClass());
		r.getMetadata().put("Updated", object.updated());
		return r;
	}

	/**
	 * Streams this object's content. For an object resource (path-less by default), the object is
	 * downloaded to a local temp file on first access and reused from {@link GcsTempFiles} on later
	 * accesses. The temp file is deliberately <b>not</b> adopted as this resource's {@link #getPath()
	 * path}: keeping the resource path-less preserves its {@linkplain #isGcpResource virtual identity}
	 * so activating it (Enter) still routes to this plugin — which opens the object's Cloud Console
	 * page — rather than the host opening the downloaded temp file in a local application.
	 * The host's built-in quick-view providers select by {@link #getName() name} and read through
	 * this stream, so no GCS-specific viewer is needed.
	 */
	@Override
	public InputStream openInputStream(OpenOption... options) throws Exception {
		if (!KIND_OBJECT.equals(getMetadata().get(KIND))) {
			return super.openInputStream(options);
		}
		return Files.newInputStream(materialize(), options);
	}

	/** Download-once: returns the local temp file backing this object, fetching it if needed. */
	private synchronized Path materialize() throws IOException {
		long startNanos = System.nanoTime();
		String bucket = bucketName(this);
		String key = objectKey(this);
		if (bucket == null || key == null) {
			throw new IOException("Not a downloadable GCS object: " + getName());
		}

		// Reuse a previous download of the same object, even across listing rebuilds. The mapping
		// lives in GcsTempFiles (keyed by gs:// key), so we never stamp the path onto the resource.
		String gsKey = bucket + "/" + key;
		Path cached = GcsTempFiles.cached(gsKey);
		if (cached != null) {
			log.info("Quick view gs://{}: served from local cache ({} ms)", gsKey, millisSince(startNanos));
			return cached;
		}

		Path temp = Files.createTempFile("nuclr-gcs-", "-" + sanitize(getName()));
		GcsObjectDownloader.Result result = new GcsObjectDownloader().downloadToFile(bucket, key, temp);
		if (result instanceof GcsObjectDownloader.Result.Err err) {
			Files.deleteIfExists(temp);
			throw new IOException("Failed to download gs://" + bucket + "/" + key + ": " + err.error());
		}
		GcsTempFiles.register(gsKey, temp);
		log.info("Quick view gs://{}: downloaded and ready in {} ms", gsKey, millisSince(startNanos));
		return temp;
	}

	private static long millisSince(long startNanos) {
		return (System.nanoTime() - startNanos) / 1_000_000L;
	}

	/** Make an object name safe to use as a temp-file suffix while preserving its extension. */
	private static String sanitize(String name) {
		if (name == null || name.isBlank()) {
			return "object";
		}
		return name.replaceAll("[^A-Za-z0-9._-]", "_");
	}

	/** The synthetic "▼ Load more…" entry that fetches the next page of the same listing. */
	static GcpResource loadMore(String projectId, String bucket, String prefix) {
		GcpResource r = new GcpResource();
		r.setUuid(ROOT_UUID + "bucket/" + bucket + "/" + prefix + " load-more");
		r.setFullPath(r.getUuid());
		r.setFolder(true);
		r.getMetadata().put(KIND, KIND_LOAD_MORE);
		r.getMetadata().put(PROJECT_ID, projectId);
		r.getMetadata().put(BUCKET, bucket);
		r.getMetadata().put(PREFIX, prefix);
		r.rename("▼ Load more…");
		r.getMetadata().put("Size", "-");
		r.getMetadata().put("Storage class", "-");
		r.getMetadata().put("Updated", "-");
		return r;
	}

	/** Set the name and keep the "Name" display column (read from metadata) in sync. */
	private void rename(String newName) {
		setName(newName);
		getMetadata().put("Name", newName);
	}

	private static String blankToDash(String value) {
		return value == null || value.isBlank() ? "-" : value;
	}

	static boolean isGcpResource(NuclrResource resource) {
		return resource != null
				&& resource.getPath() == null
				&& Boolean.TRUE.equals(resource.getMetadata().get(MARKER));
	}

	static boolean isRoot(NuclrResource resource) {
		return resource != null && KIND_ROOT.equals(resource.getMetadata().get(KIND));
	}

	static boolean isProject(NuclrResource resource) {
		return resource != null && KIND_PROJECT.equals(resource.getMetadata().get(KIND));
	}

	static boolean isService(NuclrResource resource) {
		return resource != null && KIND_SERVICE.equals(resource.getMetadata().get(KIND));
	}

	static boolean isBucket(NuclrResource resource) {
		return resource != null && KIND_BUCKET.equals(resource.getMetadata().get(KIND));
	}

	static boolean isObjectDir(NuclrResource resource) {
		return resource != null && KIND_OBJECT_DIR.equals(resource.getMetadata().get(KIND));
	}

	static boolean isLoadMore(NuclrResource resource) {
		return resource != null && KIND_LOAD_MORE.equals(resource.getMetadata().get(KIND));
	}

	static boolean isObject(NuclrResource resource) {
		return resource != null && KIND_OBJECT.equals(resource.getMetadata().get(KIND));
	}

	/** The full object key (prefix + name) carried by an object resource, or {@code null}. */
	static String objectKey(NuclrResource resource) {
		return metaString(resource, OBJECT_KEY);
	}

	/**
	 * The Cloud Console "object details" URL for an object resource, e.g.
	 * {@code https://console.cloud.google.com/storage/browser/_details/bucket/a/b.jpg;tab=live_object?project=my-proj},
	 * or {@code null} if the resource is not an object. Opened when the user activates the object.
	 */
	static String objectConsoleUrl(NuclrResource resource) {
		if (!isObject(resource)) {
			return null;
		}
		String bucket = bucketName(resource);
		String key = objectKey(resource);
		if (bucket == null || key == null) {
			return null;
		}
		var url = new StringBuilder("https://console.cloud.google.com/storage/browser/_details/")
				.append(encodePath(bucket)).append('/').append(encodePath(key))
				.append(";tab=live_object");
		String projectId = projectId(resource);
		if (projectId != null && !projectId.isBlank()) {
			url.append("?project=").append(URLEncoder.encode(projectId, StandardCharsets.UTF_8));
		}
		return url.toString();
	}

	/** URL-encode a slash-separated path, encoding each segment but preserving the {@code /} separators. */
	private static String encodePath(String path) {
		String[] segments = path.split("/", -1);
		var out = new StringBuilder();
		for (int i = 0; i < segments.length; i++) {
			if (i > 0) {
				out.append('/');
			}
			out.append(URLEncoder.encode(segments[i], StandardCharsets.UTF_8).replace("+", "%20"));
		}
		return out.toString();
	}

	/** The bucket name carried by a bucket / object-dir / load-more resource, or {@code null}. */
	static String bucketName(NuclrResource resource) {
		return metaString(resource, BUCKET);
	}

	/** The object-key prefix carried by a bucket / object-dir / load-more resource, or {@code ""}. */
	static String objectPrefix(NuclrResource resource) {
		String prefix = metaString(resource, PREFIX);
		return prefix == null ? "" : prefix;
	}

	/**
	 * The ".." target for an object listing: the parent prefix's directory, or the GCS
	 * bucket list (the service node) when already at the bucket root.
	 */
	static GcpResource objectParent(String projectId, String bucket, String prefix) {
		if (prefix == null || prefix.isEmpty()) {
			GcpResource r = gcsService(projectId); // ".." → back to the bucket list
			r.rename("..");
			return r;
		}
		String parent = parentPrefix(prefix);
		GcpResource r = objectDir(projectId, bucket, parent, "..");
		return r;
	}

	/** {@code "a/b/c/"} → {@code "a/b/"}; {@code "a/"} → {@code ""}. */
	static String parentPrefix(String prefix) {
		String trimmed = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
		int slash = trimmed.lastIndexOf('/');
		return slash < 0 ? "" : trimmed.substring(0, slash + 1);
	}

	/** The owning project id of a project, service, or bucket resource, or {@code null}. */
	static String projectId(NuclrResource resource) {
		return metaString(resource, PROJECT_ID);
	}

	/** The service type ({@link #SERVICE_GCS} / {@link #SERVICE_PUBSUB}) of a service resource, or {@code null}. */
	static String serviceType(NuclrResource resource) {
		return metaString(resource, SERVICE);
	}

	private static String metaString(NuclrResource resource, String key) {
		if (resource == null) {
			return null;
		}
		Object value = resource.getMetadata().get(key);
		return value == null ? null : value.toString();
	}
}
