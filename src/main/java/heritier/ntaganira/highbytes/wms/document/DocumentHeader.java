package heritier.ntaganira.highbytes.wms.document;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.document
 * - File       : DocumentHeader.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The spine row of a document, as the engine reads it
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.branch.BranchView;

import java.time.LocalDate;
import java.util.UUID;

/**
 * What the engine needs to know about a document: which one, where, in what
 * state, bound to which chain, raised by whom.
 *
 * <p>Carries the branch's name and flags so a refusal can be audited without
 * another query. After a database refusal the transaction is aborted and
 * cannot be asked anything.
 */
public record DocumentHeader(
        UUID id,
        DocumentKind kind,
        UUID branchId,
        String branchCode,
        String branchName,
        boolean branchBonded,
        String branchType,
        String serialNo,
        String status,
        UUID workflowDefinitionId,
        LocalDate documentDate,
        UUID createdBy,
        UUID postedBy,
        int version
) {

    public BranchView branch() {
        return new BranchView(branchId, branchCode, branchName, branchBonded, branchType);
    }

    /** How the document reads in the audit trail: "GRN · GRN-KGL-2026-0001". */
    public String label() {
        return kind.code() + " · " + serialNo;
    }

    public boolean hasChain() {
        return workflowDefinitionId != null;
    }
}
