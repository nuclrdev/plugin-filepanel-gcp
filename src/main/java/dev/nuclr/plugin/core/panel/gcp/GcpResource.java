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

	/** Metadata key distinguishing the root mount ({@link #KIND_ROOT}) from a project. */
	static final String KIND = "nuclr.gcp.kind";

	static final String KIND_ROOT = "root";
	static final String KIND_PROJECT = "project";

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
		GcpResource r = new GcpResource();
		String id = project.projectId();
		r.setUuid(ROOT_UUID + "project/" + id);
		r.setFullPath(ROOT_UUID + "project/" + id);
		r.setFolder(true);
		r.getMetadata().put(KIND, KIND_PROJECT);
		r.rename(id);
		r.getMetadata().put("Project Name", blankToDash(project.name()));
		r.getMetadata().put("Number", project.projectNumber() > 0 ? String.valueOf(project.projectNumber()) : "-");
		r.getMetadata().put("State", blankToDash(project.lifecycleState()));
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
}
