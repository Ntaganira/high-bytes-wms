-- =====================================================================
-- V8 — Item categories
--
-- The categories HIGH BYTES actually trades in, from the policy documents:
-- glass, silicones, stainless steel and construction materials.
--
-- Categories are optional on an item. They exist to make a 4,000-line item
-- master searchable, not to enforce anything.
-- =====================================================================

INSERT INTO item_category (code, name) VALUES
    ('GLASS',     'Glass'),
    ('SILICONE',  'Silicones & sealants'),
    ('STEEL',     'Stainless steel'),
    ('HARDWARE',  'Hardware & fittings'),
    ('CONSUM',    'Consumables');

-- Glass sub-categories: the shapes a cutting order and a dispatch care about.
INSERT INTO item_category (code, name, parent_id)
SELECT v.code, v.name, p.id
  FROM (VALUES
        ('GL-FLOAT',  'Float glass'),
        ('GL-TINTED', 'Tinted glass'),
        ('GL-MIRROR', 'Mirror'),
        ('GL-LAM',    'Laminated glass'),
        ('GL-TEMP',   'Tempered glass')
       ) AS v(code, name)
  JOIN item_category p ON p.code = 'GLASS';
