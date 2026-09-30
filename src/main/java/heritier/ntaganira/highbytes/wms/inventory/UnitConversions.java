package heritier.ntaganira.highbytes.wms.inventory;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory
 * - File       : UnitConversions.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Turns a quantity in any unit into the item's base unit, exactly as the database checks it
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.common.db.ControlRefusedException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Types;
import java.util.UUID;

/**
 * The ledger carries base quantities, so every document line derives one:
 * quantity times the conversion factor (1 for the base unit itself), rounded
 * half up to three places, which is exactly the rounding the document
 * triggers check it against ({@code uom_factor_to_base}).
 */
@Service
@Transactional(readOnly = true)
public class UnitConversions {

    private final JdbcClient jdbc;

    public UnitConversions(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public BigDecimal baseQuantity(UUID itemId, UUID uomId, BigDecimal quantity) {
        record Conversion(String item, String unit, String base, BigDecimal factor) {}
        Conversion c = jdbc.sql("""
                SELECT i.item_code, u.code AS uom_code, bu.code AS base_code,
                       uom_factor_to_base(i.id, u.id) AS factor
                  FROM item i JOIN uom u ON u.id = :uom JOIN uom bu ON bu.id = i.base_uom_id
                 WHERE i.id = :item
                """)
                .param("item", itemId, Types.OTHER)
                .param("uom", uomId, Types.OTHER)
                .query((rs, n) -> new Conversion(rs.getString("item_code"), rs.getString("uom_code"),
                        rs.getString("base_code"), rs.getBigDecimal("factor")))
                .optional()
                .orElseThrow(() -> new ControlRefusedException("A line names an item or unit that does not exist."));
        if (c.factor() == null) {
            throw new ControlRefusedException("Item " + c.item() + " has no conversion from " + c.unit()
                    + " to its base unit " + c.base() + ", so the stock quantity cannot be worked out. "
                    + "Use " + c.base() + ", or have a conversion added to the item.");
        }
        return quantity.multiply(c.factor()).setScale(3, RoundingMode.HALF_UP);
    }
}
