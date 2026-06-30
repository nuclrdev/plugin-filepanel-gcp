package dev.nuclr.plugin.core.panel.gcp;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import dev.nuclr.platform.plugin.NuclrResource;

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

	/** Metadata carrying the owning project id (on project, service, and bucket resources). */
	static final String PROJECT_ID = "nuclr.gcp.projectId";

	/** Metadata key identifying which service a {@link #KIND_SERVICE} resource represents. */
	static final String SERVICE = "nuclr.gcp.service";

	static final String SERVICE_GCS = "gcs";
	static final String SERVICE_PUBSUB = "pubsub";

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

	/** A Cloud Storage bucket entry shown under a project's GCS service. */
	static GcpResource bucket(GcsBucket bucket) {
		GcpResource r = new GcpResource();
		String name = bucket.name();
		r.setUuid(ROOT_UUID + "bucket/" + name);
		r.setFullPath(ROOT_UUID + "bucket/" + name);
		r.setFolder(false);
		r.getMetadata().put(KIND, KIND_BUCKET);
		r.rename(name);
		r.getMetadata().put("Created", bucket.created());
		r.getMetadata().put("Location type", bucket.locationType());
		r.getMetadata().put("Location", bucket.location());
		r.getMetadata().put("Default storage class", bucket.defaultStorageClass());
		r.getMetadata().put("Last modified", bucket.lastModified());
		r.getMetadata().put("Public Access", bucket.publicAccess());
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

	/** The owning project id of a project, service, or bucket resource, or {@code null}. */
	static String projectId(NuclrResource resource) {
		if (resource == null) {
			return null;
		}
		Object value = resource.getMetadata().get(PROJECT_ID);
		return value == null ? null : value.toString();
	}

	/** The service type ({@link #SERVICE_GCS} / {@link #SERVICE_PUBSUB}) of a service resource, or {@code null}. */
	static String serviceType(NuclrResource resource) {
		if (resource == null) {
			return null;
		}
		Object value = resource.getMetadata().get(SERVICE);
		return value == null ? null : value.toString();
	}
}
