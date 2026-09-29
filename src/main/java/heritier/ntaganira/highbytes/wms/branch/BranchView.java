package heritier.ntaganira.highbytes.wms.branch;

import java.util.UUID;

/** A trading location. Kigali is the main branch; further branches are configuration. */
public record BranchView(UUID id, String code, String name, boolean bonded, String branchType) {

    /** Kigali and Rubavu share one palette; a bonded branch is flagged, not coloured. */
    public String displayLabel() {
        return bonded ? name + " (bonded)" : name;
    }
}
