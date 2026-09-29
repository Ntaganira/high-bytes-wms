package heritier.ntaganira.highbytes.wms.masterdata.item;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.masterdata.item
 * - File       : ItemService.java
 * - Date       : 2026-09-29
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The item master: search, create, update, deactivate and reactivate, audited
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.branch.BranchView;
import heritier.ntaganira.highbytes.wms.common.audit.AuditAction;
import heritier.ntaganira.highbytes.wms.common.audit.AuditService;
import heritier.ntaganira.highbytes.wms.common.audit.AuditSnapshot;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The item master.
 *
 * <p>Items are shared across every branch; stock is not. An item that has
 * been transacted is never deleted — only deactivated — because a movement
 * pointing at a missing item makes the ledger unreadable (FR-MD-10).
 */
@Service
@Transactional(readOnly = true)
public class ItemService {

    private static final String SELECT = """
            SELECT i.id, i.item_code, i.description, c.name AS category_name,
                   i.product_type, i.colour, i.thickness_mm, i.width_mm, i.height_mm,
                   u.code AS base_uom_code, i.is_remnant,
                   parent.item_code AS cut_from_code,
                   i.reorder_level, i.max_stock_level, i.is_active,
                   COALESCE(onhand.qty, 0) AS total_on_hand
              FROM item i
              JOIN uom u                ON u.id = i.base_uom_id
         LEFT JOIN item_category c       ON c.id = i.category_id
         LEFT JOIN item parent           ON parent.id = i.cut_from_item_id
         LEFT JOIN LATERAL (
                   SELECT SUM(sb.qty_on_hand) AS qty
                     FROM stock_balance sb
                     JOIN location l ON l.id = sb.location_id
                    WHERE sb.item_id = i.id
                      AND (:branchId::uuid IS NULL OR l.branch_id = :branchId::uuid)
              ) onhand ON TRUE
            """;

    private final JdbcClient jdbc;
    private final AuditService audit;

    public ItemService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ---- reads -----------------------------------------------------------

    /**
     * The list, filtered.
     *
     * <p>Search covers code and description together, because an officer
     * holding a delivery note knows one or the other, rarely both.
     */
    public List<ItemRow> search(UUID branchId, String query, ProductType type,
                                boolean includeInactive, boolean belowReorderOnly) {

        String sql = SELECT + """
             WHERE (:query IS NULL OR i.item_code ILIKE :like OR i.description ILIKE :like)
               AND (:type::text IS NULL OR i.product_type = :type)
               AND (:includeInactive OR i.is_active)
             ORDER BY i.is_active DESC, i.item_code
             LIMIT 300
            """;

        var rows = jdbc.sql(sql)
                .param("branchId", branchId, Types.OTHER)
                .param("query", blankToNull(query))
                .param("like", query == null ? null : "%" + query.trim() + "%")
                .param("type", type == null ? null : type.name())
                .param("includeInactive", includeInactive)
                .query(this::map)
                .list();

        return belowReorderOnly ? rows.stream().filter(ItemRow::belowReorder).toList() : rows;
    }

    public Optional<ItemRow> findById(UUID id, UUID branchId) {
        return jdbc.sql(SELECT + " WHERE i.id = :id")
                .param("branchId", branchId, Types.OTHER)
                .param("id", id, Types.OTHER)
                .query(this::map)
                .optional();
    }

    /** True once anything has moved against this item: it may no longer be deleted. */
    public boolean hasBeenTransacted(UUID itemId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM stock_movement WHERE item_id = :id)")
                .param("id", itemId, Types.OTHER)
                .query(Boolean.class)
                .single();
    }

    public ItemForm formFor(UUID id) {
        return jdbc.sql("""
                SELECT id, item_code, description, category_id, product_type, colour,
                       thickness_mm, width_mm, height_mm, base_uom_id,
                       reorder_level, max_stock_level, is_active
                  FROM item WHERE id = :id
                """)
                .param("id", id, Types.OTHER)
                .query((rs, n) -> {
                    var f = new ItemForm();
                    f.setId(rs.getObject("id", UUID.class));
                    f.setItemCode(rs.getString("item_code"));
                    f.setDescription(rs.getString("description"));
                    f.setCategoryId(rs.getObject("category_id", UUID.class));
                    f.setProductType(ProductType.valueOf(rs.getString("product_type")));
                    f.setColour(rs.getString("colour"));
                    f.setThicknessMm(rs.getBigDecimal("thickness_mm"));
                    f.setWidthMm(rs.getBigDecimal("width_mm"));
                    f.setHeightMm(rs.getBigDecimal("height_mm"));
                    f.setBaseUomId(rs.getObject("base_uom_id", UUID.class));
                    f.setReorderLevel(rs.getBigDecimal("reorder_level"));
                    f.setMaxStockLevel(rs.getBigDecimal("max_stock_level"));
                    f.setActive(rs.getBoolean("is_active"));
                    return f;
                })
                .single();
    }

    // ---- writes ----------------------------------------------------------

    @Transactional
    @PreAuthorize("hasAuthority('item.manage')")
    public UUID create(ItemForm form, BranchView branch) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    INSERT INTO item (id, item_code, description, category_id, product_type,
                                      colour, thickness_mm, width_mm, height_mm, base_uom_id,
                                      reorder_level, max_stock_level, is_active)
                    VALUES (:id, :code, :description, :categoryId, :productType,
                            :colour, :thickness, :width, :height, :baseUomId,
                            :reorderLevel, :maxStockLevel, :active)
                    """)
                    .param("id", id, Types.OTHER)
                    .param("code", form.getItemCode())
                    .param("description", form.getDescription())
                    .param("categoryId", form.getCategoryId(), Types.OTHER)
                    .param("productType", form.getProductType().name())
                    .param("colour", form.getColour())
                    .param("thickness", form.getThicknessMm())
                    .param("width", form.getWidthMm())
                    .param("height", form.getHeightMm())
                    .param("baseUomId", form.getBaseUomId(), Types.OTHER)
                    .param("reorderLevel", form.getReorderLevel())
                    .param("maxStockLevel", form.getMaxStockLevel())
                    .param("active", form.isActive())
                    .update();
        } catch (DuplicateKeyException e) {
            throw new ItemCodeTakenException(form.getItemCode());
        }

        audit.record("item", id, label(form), AuditAction.CREATE,
                snapshotOf(null, form), branch, null);
        return id;
    }

    @Transactional
    @PreAuthorize("hasAuthority('item.manage')")
    public void update(UUID id, ItemForm form, BranchView branch) {
        ItemForm before = formFor(id);

        // Changing the thickness of an item that has already moved rewrites
        // what past dispatches claim was verified at the gate. Blocked.
        if (hasBeenTransacted(id)
                && before.getThicknessMm() != null
                && form.getThicknessMm() != null
                && before.getThicknessMm().compareTo(form.getThicknessMm()) != 0) {
            throw new ItemSpecificationLockedException(before.getItemCode());
        }

        try {
            jdbc.sql("""
                    UPDATE item
                       SET item_code = :code, description = :description,
                           category_id = :categoryId, product_type = :productType,
                           colour = :colour, thickness_mm = :thickness,
                           width_mm = :width, height_mm = :height,
                           base_uom_id = :baseUomId, reorder_level = :reorderLevel,
                           max_stock_level = :maxStockLevel, is_active = :active,
                           updated_at = now()
                     WHERE id = :id
                    """)
                    .param("id", id, Types.OTHER)
                    .param("code", form.getItemCode())
                    .param("description", form.getDescription())
                    .param("categoryId", form.getCategoryId(), Types.OTHER)
                    .param("productType", form.getProductType().name())
                    .param("colour", form.getColour())
                    .param("thickness", form.getThicknessMm())
                    .param("width", form.getWidthMm())
                    .param("height", form.getHeightMm())
                    .param("baseUomId", form.getBaseUomId(), Types.OTHER)
                    .param("reorderLevel", form.getReorderLevel())
                    .param("maxStockLevel", form.getMaxStockLevel())
                    .param("active", form.isActive())
                    .update();
        } catch (DuplicateKeyException e) {
            throw new ItemCodeTakenException(form.getItemCode());
        }

        var snapshot = snapshotOf(before, form);
        if (!snapshot.unchanged()) {
            audit.record("item", id, label(form),
                    form.isActive() ? AuditAction.UPDATE : AuditAction.DEACTIVATE,
                    snapshot, branch, null);
        }
    }

    /** Deactivation, never deletion, once transacted. */
    @Transactional
    @PreAuthorize("hasAuthority('item.manage')")
    public void setActive(UUID id, boolean active, String reason, BranchView branch) {
        ItemForm before = formFor(id);
        if (before.isActive() == active) return;

        jdbc.sql("UPDATE item SET is_active = :active, updated_at = now() WHERE id = :id")
                .param("id", id, Types.OTHER)
                .param("active", active)
                .update();

        audit.record("item", id, label(before),
                active ? AuditAction.UPDATE : AuditAction.DEACTIVATE,
                AuditSnapshot.of().field("Active", before.isActive(), active),
                branch, reason);
    }

    // ---- helpers ---------------------------------------------------------

    /**
     * The fields worth auditing. Labels are what a reviewer reads, not column
     * names, because the audit screen is read by the Internal Controller and
     * an external auditor, not by a developer.
     */
    private AuditSnapshot snapshotOf(ItemForm before, ItemForm after) {
        var snap = AuditSnapshot.of();
        snap.field("Item code",       before == null ? null : before.getItemCode(),      after.getItemCode());
        snap.field("Description",     before == null ? null : before.getDescription(),   after.getDescription());
        snap.field("Product type",    before == null ? null : before.getProductType(),   after.getProductType());
        snap.field("Colour",          before == null ? null : before.getColour(),        after.getColour());
        snap.field("Thickness (mm)",  before == null ? null : before.getThicknessMm(),   after.getThicknessMm());
        snap.field("Width (mm)",      before == null ? null : before.getWidthMm(),       after.getWidthMm());
        snap.field("Height (mm)",     before == null ? null : before.getHeightMm(),      after.getHeightMm());
        snap.field("Reorder level",   before == null ? null : before.getReorderLevel(),  after.getReorderLevel());
        snap.field("Maximum level",   before == null ? null : before.getMaxStockLevel(), after.getMaxStockLevel());
        snap.field("Active",          before == null ? null : before.isActive(),         after.isActive());
        return snap;
    }

    private String label(ItemForm form) {
        return "Item · " + form.getItemCode();
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private ItemRow map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ItemRow(
                rs.getObject("id", UUID.class),
                rs.getString("item_code"),
                rs.getString("description"),
                rs.getString("category_name"),
                ProductType.valueOf(rs.getString("product_type")),
                rs.getString("colour"),
                rs.getBigDecimal("thickness_mm"),
                rs.getBigDecimal("width_mm"),
                rs.getBigDecimal("height_mm"),
                rs.getString("base_uom_code"),
                rs.getBoolean("is_remnant"),
                rs.getString("cut_from_code"),
                rs.getBigDecimal("reorder_level"),
                rs.getBigDecimal("max_stock_level"),
                rs.getBoolean("is_active"),
                rs.getBigDecimal("total_on_hand") == null
                        ? BigDecimal.ZERO : rs.getBigDecimal("total_on_hand"));
    }

    // ---- failures the user should see, not a stack trace -------------------

    public static class ItemCodeTakenException extends RuntimeException {
        public ItemCodeTakenException(String code) {
            super("Item code " + code + " is already in use.");
        }
    }

    public static class ItemSpecificationLockedException extends RuntimeException {
        public ItemSpecificationLockedException(String code) {
            super("Thickness cannot be changed on " + code
                  + ": stock has already moved against it, and past dispatches record "
                  + "that specification as verified. Create a new item instead.");
        }
    }
}
