package heritier.ntaganira.highbytes.wms.inventory.dispatch;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.dispatch
 * - File       : DeliveryNoteService.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Delivery notes: raise against a released authorization, cancel, and post at the gate to the ledger
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.branch.BranchService;
import heritier.ntaganira.highbytes.wms.common.audit.AuditAction;
import heritier.ntaganira.highbytes.wms.common.audit.AuditSnapshot;
import heritier.ntaganira.highbytes.wms.common.db.ContentionException;
import heritier.ntaganira.highbytes.wms.common.db.ControlRefusedException;
import heritier.ntaganira.highbytes.wms.common.db.DbRefusal;
import heritier.ntaganira.highbytes.wms.common.db.KigaliTime;
import heritier.ntaganira.highbytes.wms.document.DocumentHeader;
import heritier.ntaganira.highbytes.wms.document.DocumentKind;
import heritier.ntaganira.highbytes.wms.document.DocumentService;
import heritier.ntaganira.highbytes.wms.document.OpenedDocument;
import heritier.ntaganira.highbytes.wms.document.StepCheck;
import heritier.ntaganira.highbytes.wms.inventory.damage.PartyCheck;
import heritier.ntaganira.highbytes.wms.inventory.gate.ReleaseGate;
import heritier.ntaganira.highbytes.wms.inventory.ledger.LedgerService;
import heritier.ntaganira.highbytes.wms.inventory.ledger.MovementRequest;
import heritier.ntaganira.highbytes.wms.inventory.lookup.BinOption;
import heritier.ntaganira.highbytes.wms.inventory.lookup.StockAt;
import heritier.ntaganira.highbytes.wms.security.CurrentUser;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Delivery notes: the gate.
 *
 * <p>A note is raised against an authorization that is RELEASED (fully
 * signed, the Internal Controller's release last), loads exactly what it
 * authorized (one load, bin splits allowed, the parts adding up), and is posted
 * by the warehouse at the gate. Posting is one transaction: the note goes
 * POSTED, a DELIVERY ticket is raised, one OUT movement is written per ticket
 * line through the ledger, {@code stock_balance} moves with it, and the ticket
 * goes POSTED. If any of it is refused (an unsigned authorization, a poster
 * who raised the authorization, a load that is not the authorization, stock the
 * shelf does not hold, a locked business day), all of it is.
 *
 * <p>The rules are the database's (V12). This class asks first where a
 * friendlier reason helps, and otherwise passes the database's own through.
 */
@Service
@Transactional(readOnly = true)
public class DeliveryNoteService {

    private static final String HEADER = """
            SELECT d.id, d.branch_id, b.name AS branch_name, b.is_bonded AS branch_bonded,
                   d.serial_no, d.status, d.version,
                   d.created_by, cu.full_name AS created_by_name, d.created_at, d.posted_at,
                   d.posted_by, pu.full_name AS posted_by_name,
                   d.cancelled_at, xu.full_name AS cancelled_by_name, d.cancel_reason,
                   na.authority_id AS authorization_id, na.authority_kind,
                   dao.serial_no AS dao_serial, dao.status AS dao_status,
                   dao.created_by AS dao_created_by, dcu.full_name AS dao_created_by_name,
                   dao.posted_by AS authority_posted_by,
                   c.name AS customer_name,
                   na.location_id, l.code AS location_code, l.name AS location_name, l.is_bonded AS location_bonded,
                   na.customs_reference,
                   n.vehicle_registration, n.driver_name, n.driver_phone, n.driver_id_no
              FROM document d
              JOIN document_type dt           ON dt.id = d.document_type_id AND dt.code = 'DN'
              JOIN delivery_note n            ON n.document_id = d.id
              -- The note's authority: its delivery authorization, or its cutting order (V18).
              JOIN delivery_note_authority na ON na.note_id = n.document_id
              JOIN document dao               ON dao.id = na.authority_id
              JOIN customer c                 ON c.id = na.customer_id
              JOIN location l                 ON l.id = na.location_id
              JOIN branch b                 ON b.id = d.branch_id
              JOIN app_user cu              ON cu.id = d.created_by
              JOIN app_user dcu             ON dcu.id = dao.created_by
         LEFT JOIN app_user pu              ON pu.id = d.posted_by
         LEFT JOIN app_user xu              ON xu.id = d.cancelled_by
             WHERE d.id = :id
            """;

    private static final String LINES = """
            SELECT dl.id, dl.line_no, COALESCE(dl.authorization_line_id, dl.cutting_output_id) AS authorization_line_id,
                   al.line_no AS dao_line_no,
                   dl.item_id, i.item_code, i.description, i.product_type, i.thickness_mm,
                   dl.uom_id, u.code AS uom_code, bu.code AS base_uom_code,
                   dl.quantity, dl.qty_base_uom, dl.storage_bin_id, sb.bin_code, dl.measured_thickness_mm,
                   delivery_line_returned(dl.id) AS returned_base
              FROM delivery_note_line dl
              JOIN delivery_authority_line al ON al.id = COALESCE(dl.authorization_line_id, dl.cutting_output_id)
              JOIN item i  ON i.id = dl.item_id
              JOIN uom u   ON u.id = dl.uom_id
              JOIN uom bu  ON bu.id = i.base_uom_id
         LEFT JOIN storage_bin sb ON sb.id = dl.storage_bin_id
             WHERE dl.document_id = :id
             ORDER BY dl.line_no
            """;

    /** What the form and the view draw beside the note: the authorization, its lines, the gate, the stock, the bins. */
    public record Context(DaoHeader dao, List<DaoLineRow> daoLines, ReleaseGate gate,
                          Map<UUID, List<StockAt>> stock, List<BinOption> bins) {}

    /** Everything the note's own view needs. */
    public record Detail(DnHeader header, List<DnLineRow> lines, ReleaseGate gate, Map<UUID, List<StockAt>> stock,
                         DnActions actions, DnPosting posting,
                         List<ReturnSummary> returns, boolean canReturn, String returnReason) {}

    /** One customer-return report written against the note, for the note's view. */
    public record ReturnSummary(UUID id, String serialNo, String status, java.time.LocalDateTime postedAt,
                                String postedByName) {}

    private final JdbcClient jdbc;
    private final DocumentService documents;
    private final List<DeliveryAuthority> authorities;
    private final DispatchLookupService lookups;
    private final LedgerService ledger;
    private final BranchService branches;
    private final PartyCheck parties;

    public DeliveryNoteService(JdbcClient jdbc, DocumentService documents, List<DeliveryAuthority> authorities,
                               DispatchLookupService lookups, LedgerService ledger, BranchService branches,
                               PartyCheck parties) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.authorities = authorities;
        this.lookups = lookups;
        this.ledger = ledger;
        this.branches = branches;
        this.parties = parties;
    }

    // ---- reads -----------------------------------------------------------

    /**
     * What the gate may load now at the branch: released authorizations, and posted cutting orders whose pieces
     * are in stock, with no live delivery note.
     */
    @PreAuthorize("hasAuthority('dispatch.post')")
    public List<ReadyRow> readyToLoad(UUID branchId) {
        return jdbc.sql("""
                SELECT * FROM (
                SELECT 'DAO' AS kind, d.id, d.serial_no, c.name AS customer_name, l.code AS location_code,
                       (b.is_bonded OR l.is_bonded) AS bonded,
                       (SELECT COUNT(*) FROM delivery_authorization_line dl WHERE dl.document_id = d.id) AS line_count,
                       rel.actor_name, rel.decided_at
                  FROM document d
                  JOIN document_type dt         ON dt.id = d.document_type_id AND dt.code = 'DAO'
                  JOIN delivery_authorization a ON a.document_id = d.id
                  JOIN branch b                 ON b.id = d.branch_id
                  JOIN customer c               ON c.id = a.customer_id
                  JOIN location l               ON l.id = a.location_id
             LEFT JOIN LATERAL (
                       SELECT da.actor_name, da.decided_at
                         FROM document_approval da JOIN workflow_step ws ON ws.id = da.workflow_step_id
                        WHERE da.document_id = d.id ORDER BY ws.sequence_no DESC LIMIT 1) rel ON TRUE
                 WHERE d.branch_id = :branch AND d.status = 'APPROVED'
                   AND NOT EXISTS (SELECT 1 FROM delivery_note n JOIN document dn ON dn.id = n.document_id
                                    WHERE n.authorization_id = d.id AND dn.status <> 'CANCELLED')
                UNION ALL
                -- A cutting order's pieces load once it is posted: they are in the ledger only then (V18).
                SELECT 'CUT', d.id, d.serial_no, c.name, l.code, (b.is_bonded OR l.is_bonded),
                       (SELECT COUNT(*) FROM cutting_order_output o WHERE o.document_id = d.id AND o.kind = 'PIECE'),
                       pu.full_name, d.posted_at
                  FROM document d
                  JOIN document_type dt ON dt.id = d.document_type_id AND dt.code = 'CUT'
                  JOIN cutting_order co ON co.document_id = d.id
                  JOIN branch b         ON b.id = d.branch_id
                  JOIN customer c       ON c.id = co.customer_id
                  JOIN location l       ON l.id = co.location_id
             LEFT JOIN app_user pu      ON pu.id = d.posted_by
                 WHERE d.branch_id = :branch AND d.status = 'POSTED'
                   AND NOT EXISTS (SELECT 1 FROM delivery_note n JOIN document dn ON dn.id = n.document_id
                                    WHERE n.cutting_order_id = d.id AND dn.status <> 'CANCELLED')
                ) ready
                 ORDER BY ready.decided_at
                """)
                .param("branch", branchId, Types.OTHER)
                .query((rs, n) -> new ReadyRow(rs.getObject("id", UUID.class), rs.getString("serial_no"),
                        rs.getString("customer_name"), rs.getString("location_code"), rs.getBoolean("bonded"),
                        rs.getInt("line_count"), rs.getString("actor_name"), KigaliTime.read(rs, "decided_at"),
                        rs.getString("kind")))
                .list();
    }

    @PreAuthorize("hasAuthority('dispatch.view')")
    public List<DnRow> list(UUID branchId, String status) {
        return jdbc.sql("""
                SELECT d.id, d.serial_no, d.status, na.authority_id AS authorization_id, dao.serial_no AS dao_serial,
                       c.name AS customer_name, n.vehicle_registration, n.driver_name,
                       cu.full_name AS created_by_name, d.created_at, d.posted_at
                  FROM document d
                  JOIN document_type dt           ON dt.id = d.document_type_id AND dt.code = 'DN'
                  JOIN delivery_note n            ON n.document_id = d.id
                  JOIN delivery_note_authority na ON na.note_id = n.document_id
                  JOIN document dao               ON dao.id = na.authority_id
                  JOIN customer c                 ON c.id = na.customer_id
                  JOIN app_user cu              ON cu.id = d.created_by
                 WHERE d.branch_id = :branch AND (:status::text IS NULL OR d.status = :status)
                 ORDER BY d.created_at DESC LIMIT 300
                """)
                .param("branch", branchId, Types.OTHER)
                .param("status", status == null || status.isBlank() ? null : status)
                .query((rs, n) -> new DnRow(rs.getObject("id", UUID.class), rs.getString("serial_no"),
                        rs.getString("status"), rs.getObject("authorization_id", UUID.class),
                        rs.getString("dao_serial"), rs.getString("customer_name"),
                        rs.getString("vehicle_registration"), rs.getString("driver_name"),
                        rs.getString("created_by_name"), KigaliTime.read(rs, "created_at"),
                        KigaliTime.read(rs, "posted_at")))
                .list();
    }

    @PreAuthorize("hasAuthority('dispatch.view')")
    public DnHeader find(UUID id) {
        DnHeader header = requireHeader(id);
        CurrentUser.requireAt("dispatch.view", header.branchId());
        return header;
    }

    public List<DnLineRow> lines(UUID id) {
        return jdbc.sql(LINES).param("id", id, Types.OTHER)
                .query((rs, n) -> new DnLineRow(
                        rs.getObject("id", UUID.class),
                        rs.getInt("line_no"),
                        rs.getObject("authorization_line_id", UUID.class),
                        rs.getInt("dao_line_no"),
                        rs.getObject("item_id", UUID.class),
                        rs.getString("item_code"),
                        rs.getString("description"),
                        rs.getString("product_type"),
                        rs.getBigDecimal("thickness_mm"),
                        rs.getObject("uom_id", UUID.class),
                        rs.getString("uom_code"),
                        rs.getString("base_uom_code"),
                        rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("qty_base_uom"),
                        rs.getObject("storage_bin_id", UUID.class),
                        rs.getString("bin_code"),
                        rs.getBigDecimal("measured_thickness_mm"),
                        rs.getBigDecimal("returned_base")))
                .list();
    }

    /**
     * The authority the form is for (a delivery authorization, or a posted cutting order), with the gate, the
     * stock and the bins beside it.
     */
    @PreAuthorize("hasAuthority('dispatch.post')")
    public Context contextFor(UUID authorizationId) {
        CurrentUser.requireAt("dispatch.post", documents.header(authorizationId).branchId());   // a read: refused, not recorded
        DeliveryAuthority authority = authorityOf(authorizationId);
        DaoHeader dao = authority.asAuthorization(authorizationId);
        List<DaoLineRow> daoLines = authority.loadableLines(authorizationId);
        return new Context(dao, daoLines, authority.gateOf(authorizationId),
                lookups.stockAt(dao.locationId(), daoLines.stream().map(DaoLineRow::itemId).collect(Collectors.toSet())),
                lookups.binsOf(dao.locationId()));
    }

    /** A new note's form, with a line per authorization line at the authorized quantity. */
    @PreAuthorize("hasAuthority('dispatch.post')")
    public DnForm prefill(UUID authorizationId) {
        Context context = contextFor(authorizationId);
        DnForm form = new DnForm();
        form.setAuthorizationId(authorizationId);
        for (DaoLineRow line : context.daoLines()) {
            DnLineForm loaded = new DnLineForm();
            loaded.setAuthorizationLineId(line.id());
            loaded.setQuantity(line.quantity());
            form.getLines().add(loaded);
        }
        return form;
    }

    /** The note as its form holds it, for editing. */
    @PreAuthorize("hasAuthority('dispatch.post')")
    public DnForm formFor(UUID id) {
        CurrentUser.requireAt("dispatch.post", documents.header(id).branchId());   // a read: refused, not recorded
        DnHeader h = find(id);
        DnForm form = new DnForm();
        form.setId(h.id());
        form.setVersion(h.version());
        form.setAuthorizationId(h.authorizationId());
        form.setVehicleRegistration(h.vehicleRegistration());
        form.setDriverName(h.driverName());
        form.setDriverPhone(h.driverPhone());
        form.setDriverIdNo(h.driverIdNo());
        for (DnLineRow row : lines(id)) {
            DnLineForm line = new DnLineForm();
            line.setAuthorizationLineId(row.authorizationLineId());
            line.setQuantity(row.quantity());
            line.setStorageBinId(row.storageBinId());
            line.setMeasuredThicknessMm(row.measuredThicknessMm());
            form.getLines().add(line);
        }
        return form;
    }

    @PreAuthorize("hasAuthority('dispatch.view')")
    public Detail detail(UUID id) {
        DnHeader header = find(id);
        List<DnLineRow> lines = lines(id);
        ReleaseGate gate = authorityOf(header.authorizationId()).gateOf(header.authorizationId());
        Map<UUID, List<StockAt>> stock = "DRAFT".equals(header.status())
                ? lookups.stockAt(header.locationId(),
                        lines.stream().map(DnLineRow::itemId).collect(Collectors.toSet()))
                : Map.of();
        boolean posted = "POSTED".equals(header.status());
        boolean right = posted && CurrentUser.holdsAt("damage.create", header.branchId());
        boolean anyLeft = lines.stream().anyMatch(l -> l.quantityBase().compareTo(l.returnedBase()) > 0);
        String returnReason = null;
        boolean canReturn = false;
        if (right) {
            String independence = parties.reason(CurrentUser.id(), "CUSTOMER_RETURN", null, id);
            if (!anyLeft) {
                returnReason = "Everything delivered on " + header.serialNo() + " has already come back.";
            } else if (independence != null) {
                returnReason = independence;
            } else {
                canReturn = true;
            }
        }
        return new Detail(header, lines, gate, stock, actionsFor(header, gate), postingOf(id).orElse(null),
                returnsOf(id), canReturn, returnReason);
    }

    /** Customer-return reports raised against this note, oldest first. */
    public List<ReturnSummary> returnsOf(UUID noteId) {
        return jdbc.sql("""
                SELECT d.id, d.serial_no, d.status, d.posted_at, pu.full_name AS posted_by_name
                  FROM damage_report r JOIN document d ON d.id = r.document_id
             LEFT JOIN app_user pu ON pu.id = d.posted_by
                 WHERE r.delivery_note_id = :id ORDER BY d.created_at
                """)
                .param("id", noteId, Types.OTHER)
                .query((rs, n) -> new ReturnSummary(rs.getObject("id", UUID.class), rs.getString("serial_no"),
                        rs.getString("status"), KigaliTime.read(rs, "posted_at"), rs.getString("posted_by_name")))
                .list();
    }

    public Optional<DnPosting> postingOf(UUID id) {
        Optional<UUID> ticket = jdbc.sql("""
                SELECT t.document_id FROM transaction_ticket t
                 WHERE t.source_document_id = :id AND t.movement_type = 'DELIVERY'
                """).param("id", id, Types.OTHER).query(UUID.class).optional();
        if (ticket.isEmpty()) return Optional.empty();
        String serial = jdbc.sql("SELECT serial_no FROM document WHERE id = :id")
                .param("id", ticket.get(), Types.OTHER).query(String.class).single();
        List<DnPosting.Movement> movements = jdbc.sql("""
                SELECT m.id, tl.line_no, i.item_code, sb.bin_code, m.quantity_base_uom, m.unit_cost, m.value,
                       -- The balance after is the place's book while nothing has moved since: none while counted (V15).
                       CASE WHEN count_freezing(m.item_id, m.location_id) IS NULL THEN m.running_balance END AS running_balance,
                       m.business_date
                  FROM stock_movement m
                  JOIN ticket_line tl ON tl.id = m.ticket_line_id
                  JOIN item i ON i.id = m.item_id
             LEFT JOIN storage_bin sb ON sb.id = m.storage_bin_id
                 WHERE m.document_id = :ticket
                 ORDER BY tl.line_no
                """)
                .param("ticket", ticket.get(), Types.OTHER)
                .query((rs, n) -> new DnPosting.Movement(rs.getLong("id"), rs.getInt("line_no"),
                        rs.getString("item_code"), rs.getString("bin_code"), rs.getBigDecimal("quantity_base_uom"),
                        rs.getBigDecimal("unit_cost"), rs.getBigDecimal("value"), rs.getBigDecimal("running_balance"),
                        rs.getObject("business_date", java.time.LocalDate.class)))
                .list();
        return Optional.of(new DnPosting(ticket.get(), serial, movements));
    }

    // ---- writes ----------------------------------------------------------

    /**
     * Raises a note against a released authorization or a posted cutting order. The database refuses an authority
     * that is not fully signed (or, a cutting order, not yet posted), one that already has a live note, and a line
     * that is not the authority's.
     */
    @Transactional
    @PreAuthorize("hasAuthority('dispatch.post')")
    public UUID create(DnForm form) {
        documents.requireRight(documents.header(form.getAuthorizationId()), "dispatch.post", "Raise a delivery note");
        DeliveryAuthority authority = authorityOf(form.getAuthorizationId());
        DaoHeader dao = authority.asAuthorization(form.getAuthorizationId());
        boolean cut = DocumentKind.CUT.code().equals(authority.typeCode());
        try {
            OpenedDocument opened = documents.open(DocumentKind.DN, dao.branchId(), dao.serialNo(), null, null);
            jdbc.sql("""
                    INSERT INTO delivery_note (document_id, authorization_id, cutting_order_id, vehicle_registration,
                                               driver_name, driver_phone, driver_id_no)
                    VALUES (:id, :dao, :cut, :vehicle, :driver, :phone, :idNo)
                    """)
                    .param("id", opened.id(), Types.OTHER)
                    .param("dao", cut ? null : dao.id(), Types.OTHER)
                    .param("cut", cut ? dao.id() : null, Types.OTHER)
                    .params(headerParams(form))
                    .update();
            insertLines(opened.id(), form.getLines());

            DnHeader after = requireHeader(opened.id());
            documents.auditInTransaction("DN · " + after.serialNo(), opened.id(), AuditAction.CREATE,
                    snapshot(null, null, after, lines(opened.id())), branches.findById(dao.branchId()).orElse(null));
            return opened.id();
        } catch (DataAccessException e) {
            throw DbRefusal.asRefusal(e);
        }
    }

    /** Edits a draft note, provided nobody changed it since the form was opened. */
    @Transactional
    @PreAuthorize("hasAuthority('dispatch.post')")
    public void update(UUID id, DnForm form) {
        DocumentHeader d = documents.lock(id);
        documents.requireRight(d, "dispatch.post", "Edit");
        if (form.getVersion() == null) {
            throw documents.refused(d, "Edit", "This form did not say which version of " + d.serialNo()
                    + " it was opened from. Reload the note and try again.");
        }
        DnHeader before = requireHeader(id);
        List<DnLineRow> beforeLines = lines(id);
        try {
            documents.updateDraft(d, form.getVersion(), null, null);
            jdbc.sql("DELETE FROM delivery_note_line WHERE document_id = :id").param("id", id, Types.OTHER).update();
            jdbc.sql("""
                    UPDATE delivery_note SET vehicle_registration = :vehicle, driver_name = :driver,
                                             driver_phone = :phone, driver_id_no = :idNo
                     WHERE document_id = :id
                    """)
                    .param("id", id, Types.OTHER)
                    .params(headerParams(form))
                    .update();
            insertLines(id, form.getLines());
        } catch (DataAccessException e) {
            throw documents.refusedBy(d, "Edit", e);
        }
        DnHeader after = requireHeader(id);
        AuditSnapshot snapshot = snapshot(before, beforeLines, after, lines(id));
        if (!snapshot.unchanged()) {
            documents.auditInTransaction(d.label(), id, AuditAction.UPDATE, snapshot, d.branch());
        }
    }

    /** Cancels a note before it is posted. A posted note is refused: what left is corrected by a reversal. */
    @Transactional
    @PreAuthorize("hasAuthority('dispatch.post')")
    public void cancel(UUID id, String reason) {
        documents.cancel(id, reason);
    }

    /**
     * Confirms release at the gate: posts the note, raises the ticket and
     * moves the stock OUT, in one transaction. Returns the ticket's serial.
     */
    @Transactional
    @PreAuthorize("hasAuthority('dispatch.post')")
    public String post(UUID id) {
        DocumentHeader d = documents.beginPost(id);      // locks; DRAFT -> POSTED by the poster, judged by the database
        DnHeader h = requireHeader(id);
        List<DnLineRow> lines = lines(id);
        UUID poster = CurrentUser.id();

        OpenedDocument ticket;
        List<Long> movementIds = new ArrayList<>();
        BigDecimal value = BigDecimal.ZERO;
        try {
            // Every place this delivery takes from is locked up front, in one fixed order, whatever the line order.
            ledger.lockPlaces(lines.stream()
                    .map(l -> new LedgerService.Place(l.itemId(), h.locationId())).toList());
            ticket = documents.open(DocumentKind.TT, d.branchId(), d.serialNo(), null, null);

            // The ticket carries no value: an issue is valued at the average cost the ledger finds, and a
            // posted ticket's row cannot change afterwards. The values are on the movements.
            jdbc.sql("""
                    INSERT INTO transaction_ticket (document_id, movement_type, direction, from_location_id,
                                                    source_document_id, customs_reference)
                    VALUES (:id, 'DELIVERY', 'OUT', :location, :source, :customs)
                    """)
                    .param("id", ticket.id(), Types.OTHER)
                    .param("location", h.locationId(), Types.OTHER)
                    .param("source", id, Types.OTHER)
                    .param("customs", h.customsReference())
                    .update();

            List<UUID> ticketLineIds = new ArrayList<>();
            for (DnLineRow line : lines) {
                UUID lineId = UUID.randomUUID();
                ticketLineIds.add(lineId);
                jdbc.sql("""
                        INSERT INTO ticket_line (id, ticket_id, line_no, item_id, quantity, uom_id, qty_base_uom,
                                                 storage_bin_id)
                        VALUES (:id, :ticket, :lineNo, :item, :quantity, :uom, :base, :bin)
                        """)
                        .param("id", lineId, Types.OTHER)
                        .param("ticket", ticket.id(), Types.OTHER)
                        .param("lineNo", (short) line.lineNo())
                        .param("item", line.itemId(), Types.OTHER)
                        .param("quantity", line.quantity())
                        .param("uom", line.uomId(), Types.OTHER)
                        .param("base", line.quantityBase())
                        .param("bin", line.storageBinId(), Types.OTHER)
                        .update();
            }

            for (int i = 0; i < lines.size(); i++) {
                DnLineRow line = lines.get(i);
                var entry = ledger.post(MovementRequest.issue(ticket.id(), ticketLineIds.get(i), d.branchId(),
                        line.itemId(), h.locationId(), line.storageBinId(), line.quantityBase()), poster);
                movementIds.add(entry.movementId());
                value = value.add(entry.value());
            }
        } catch (ContentionException e) {
            throw e;        // a lost race, not a refusal: logged by the engine, never audited as REJECT
        } catch (ControlRefusedException e) {
            throw documents.refused(d, "Post", e.getMessage());
        } catch (DataAccessException e) {
            throw documents.refusedBy(d, "Post", e);
        }

        documents.postDerived(d, ticket.id());

        documents.auditPosted(d, AuditSnapshot.of()
                .field("Status", "DRAFT", "POSTED")
                .value(h.againstCuttingOrder() ? "Cutting order" : "Authorization", h.daoSerial())
                .value("Transaction ticket", ticket.serialNo())
                .value("Vehicle", h.vehicleRegistration())
                .value("Driver", h.driverName())
                .value("Ledger movements", movementIds.size())
                .value("Value left the ledger (RWF)", value));
        return ticket.serialNo();
    }

    // ---- helpers ---------------------------------------------------------

    /** The source of truth for a note's authority, by the authority's own document type. */
    private DeliveryAuthority authorityOf(UUID authorityId) {
        String code = documents.header(authorityId).kind().code();
        return authorities.stream().filter(a -> a.typeCode().equals(code)).findFirst()
                .orElseThrow(() -> new ControlRefusedException("A delivery note is raised against a delivery "
                        + "authorization or a posted cutting order, and nothing else."));
    }

    private DnActions actionsFor(DnHeader h, ReleaseGate gate) {
        UUID branch = h.branchId();
        boolean poster = CurrentUser.holdsAt("dispatch.post", branch);
        DocumentHeader d = documents.header(h.id());
        StepCheck cancel = documents.canCancel(d);

        boolean draft = "DRAFT".equals(h.status());
        String postReason = null;
        boolean canPost = false;
        if (draft && poster) {
            UUID me = CurrentUser.id();
            if (me != null && me.equals(h.daoCreatedBy())) {
                postReason = "You raised " + (h.againstCuttingOrder() ? "cutting order " : "authorization ")
                        + h.daoSerial() + ", so you cannot post its delivery note. "
                        + "Whoever authorizes a delivery does not also let it out of the gate.";
            } else if (h.againstCuttingOrder() && me != null && me.equals(h.authorityPostedBy())) {
                postReason = "You posted cutting order " + h.daoSerial() + ", so you cannot post its delivery note. "
                        + "Whoever records a cut does not also let what was cut out of the gate.";
            } else if (releaseSignerReason(h.daoSerial(), me, documents.chain(h.authorizationId())) != null) {
                postReason = releaseSignerReason(h.daoSerial(), me, documents.chain(h.authorizationId()));
            } else if (!gate.released()) {
                postReason = (h.againstCuttingOrder() ? "Cutting order " : "Authorization ") + h.daoSerial()
                        + (h.againstCuttingOrder() ? " is not posted" : " is not released") + ", so nothing may leave.";
            } else {
                canPost = true;
            }
        }
        return new DnActions(draft && poster, canPost, postReason, cancel.allowed(),
                "POSTED".equals(h.status()) && poster ? cancel.reason() : null);
    }

    /**
     * Mirrors the database: whoever signed the authorization's RELEASE step,
     * the Internal Controller's independent check, does not also let the goods
     * out of the gate. Null when {@code me} signed no release step.
     */
    static String releaseSignerReason(String daoSerial, UUID me, List<heritier.ntaganira.highbytes.wms.document.ChainStep> chain) {
        if (me == null) return null;
        boolean signedRelease = chain.stream().anyMatch(s -> "RELEASE".equals(s.actionLabel())
                && "APPROVED".equals(s.decision()) && me.equals(s.actorUserId()));
        return signedRelease
                ? "You signed the release of authorization " + daoSerial + ", so you cannot post its delivery note. "
                  + "The Internal Controller verifies what leaves and does not also let it out of the gate."
                : null;
    }

    private DnHeader requireHeader(UUID id) {
        return jdbc.sql(HEADER).param("id", id, Types.OTHER)
                .query(this::mapHeader).optional()
                .orElseThrow(() -> new DnNotFoundException(id));
    }

    private Map<String, Object> headerParams(DnForm form) {
        var params = new java.util.HashMap<String, Object>();
        params.put("vehicle", form.getVehicleRegistration());
        params.put("driver", form.getDriverName());
        params.put("phone", form.getDriverPhone());
        params.put("idNo", form.getDriverIdNo());
        return params;
    }

    /**
     * Lines numbered by position. Item and unit are read from the
     * authority's line the row serves (an authorization line, or a piece of a
     * cutting order); the base quantity follows from the quantity and the
     * unit's factor, rounded half up to three places, as the database checks it.
     */
    private void insertLines(UUID documentId, List<DnLineForm> lines) {
        int lineNo = 0;
        for (DnLineForm line : lines) {
            lineNo++;
            record Serves(UUID item, UUID uom, BigDecimal factor, String code, String kind) {}
            Serves serves = jdbc.sql("""
                    SELECT al.item_id, al.uom_id, uom_factor_to_base(al.item_id, al.uom_id) AS factor, i.item_code,
                           al.authority_kind
                      FROM delivery_authority_line al JOIN item i ON i.id = al.item_id
                     WHERE al.id = :id
                    """)
                    .param("id", line.getAuthorizationLineId(), Types.OTHER)
                    .query((rs, n) -> new Serves(rs.getObject("item_id", UUID.class), rs.getObject("uom_id", UUID.class),
                            rs.getBigDecimal("factor"), rs.getString("item_code"), rs.getString("authority_kind")))
                    .optional()
                    .orElseThrow(() -> new ControlRefusedException(
                            "A line names an authorization line that does not exist."));
            if (serves.factor() == null) {
                throw new ControlRefusedException("Item " + serves.code() + " has no conversion from its unit to "
                        + "its base unit, so the stock quantity cannot be worked out.");
            }
            boolean piece = "CUT".equals(serves.kind());
            jdbc.sql("""
                    INSERT INTO delivery_note_line (document_id, line_no, authorization_line_id, cutting_output_id,
                                                    item_id, uom_id, quantity, qty_base_uom, storage_bin_id,
                                                    measured_thickness_mm)
                    VALUES (:doc, :lineNo, :authLine, :cutLine, :item, :uom, :quantity, :base, :bin, :thickness)
                    """)
                    .param("doc", documentId, Types.OTHER)
                    .param("lineNo", (short) lineNo)
                    .param("authLine", piece ? null : line.getAuthorizationLineId(), Types.OTHER)
                    .param("cutLine", piece ? line.getAuthorizationLineId() : null, Types.OTHER)
                    .param("item", serves.item(), Types.OTHER)
                    .param("uom", serves.uom(), Types.OTHER)
                    .param("quantity", line.getQuantity())
                    .param("base", line.getQuantity().multiply(serves.factor()).setScale(3, RoundingMode.HALF_UP))
                    .param("bin", line.getStorageBinId(), Types.OTHER)
                    .param("thickness", line.getMeasuredThicknessMm())
                    .update();
        }
    }

    private AuditSnapshot snapshot(DnHeader before, List<DnLineRow> beforeLines, DnHeader after,
                                   List<DnLineRow> afterLines) {
        AuditSnapshot s = AuditSnapshot.of();
        s.field("Authorization",       before == null ? null : before.daoSerial(), after.daoSerial());
        s.field("Vehicle",             before == null ? null : before.vehicleRegistration(), after.vehicleRegistration());
        s.field("Driver",              before == null ? null : before.driverName(), after.driverName());
        s.field("Driver phone",        before == null ? null : before.driverPhone(), after.driverPhone());
        s.field("Driver ID",           before == null ? null : before.driverIdNo(), after.driverIdNo());
        s.field("Lines",               beforeLines == null ? null : describe(beforeLines), describe(afterLines));
        return s;
    }

    private static String describe(List<DnLineRow> lines) {
        return lines.stream().map(l -> l.lineNo() + ": " + l.itemCode() + " x "
                        + l.quantity().stripTrailingZeros().toPlainString() + " " + l.uomCode()
                        + (l.binCode() == null ? "" : ", bin " + l.binCode())
                        + (l.measuredThicknessMm() == null ? "" : ", " + l.measuredThicknessMm().stripTrailingZeros().toPlainString() + " mm"))
                .collect(Collectors.joining("; "));
    }

    private DnHeader mapHeader(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new DnHeader(
                rs.getObject("id", UUID.class),
                rs.getObject("branch_id", UUID.class),
                rs.getString("branch_name"),
                rs.getBoolean("branch_bonded"),
                rs.getString("serial_no"),
                rs.getString("status"),
                rs.getInt("version"),
                rs.getObject("created_by", UUID.class),
                rs.getString("created_by_name"),
                KigaliTime.read(rs, "created_at"),
                KigaliTime.read(rs, "posted_at"),
                rs.getObject("posted_by", UUID.class),
                rs.getString("posted_by_name"),
                KigaliTime.read(rs, "cancelled_at"),
                rs.getString("cancelled_by_name"),
                rs.getString("cancel_reason"),
                rs.getObject("authorization_id", UUID.class),
                rs.getString("dao_serial"),
                rs.getString("dao_status"),
                rs.getObject("dao_created_by", UUID.class),
                rs.getString("dao_created_by_name"),
                rs.getString("customer_name"),
                rs.getObject("location_id", UUID.class),
                rs.getString("location_code"),
                rs.getString("location_name"),
                rs.getBoolean("location_bonded"),
                rs.getString("customs_reference"),
                rs.getString("vehicle_registration"),
                rs.getString("driver_name"),
                rs.getString("driver_phone"),
                rs.getString("driver_id_no"),
                rs.getString("authority_kind"),
                rs.getObject("authority_posted_by", UUID.class));
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    public static class DnNotFoundException extends RuntimeException {
        public DnNotFoundException(UUID id) {
            super("No delivery note with id " + id);
        }
    }
}
