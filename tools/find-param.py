sql = (
    "select distinct pv1_0.id,pv1_0.is_active,pv1_0.color,pv1_0.created_at,pv1_0.demo_key,"
    "pv1_0.other_attribute,pv1_0.product_id,pv1_0.selling_price,pv1_0.size,pv1_0.sku,pv1_0.updated_at "
    "from product_variants pv1_0,products p1_0 where pv1_0.product_id=p1_0.id "
    "and (?=false or (pv1_0.is_active=true and p1_0.is_active=true)) "
    "and (? is null or lower(pv1_0.sku) like lower(('%'||?||'%')) escape '' "
    "or lower(p1_0.name) like lower(('%'||?||'%')) escape '' "
    "or exists(select vb1_0.id from variant_barcodes vb1_0 where vb1_0.variant_id=pv1_0.id "
    "and lower(vb1_0.barcode) like lower(('%'||?||'%')) escape '')) "
    "order by pv1_0.id fetch first ? rows only"
)
pos = 362
print("around position 362:")
print(repr(sql[pos - 60 : pos + 30]))
print("position of each ?:")
i = -1
while True:
    i = sql.find("?", i + 1)
    if i < 0:
        break
    print("  ? at", i + 1)
