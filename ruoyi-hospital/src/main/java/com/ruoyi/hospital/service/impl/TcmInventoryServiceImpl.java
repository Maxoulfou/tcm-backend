package com.ruoyi.hospital.service.impl;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.hospital.service.ITcmAuditLogService;
import com.ruoyi.hospital.domain.TcmConsultation;
import com.ruoyi.hospital.domain.TcmHerbDict;
import com.ruoyi.hospital.domain.TcmInventoryItem;
import com.ruoyi.hospital.mapper.TcmConsultationMapper;
import com.ruoyi.hospital.mapper.TcmInventoryItemMapper;
import com.ruoyi.hospital.service.ITcmHerbDictService;
import com.ruoyi.hospital.service.ITcmInventoryService;

@Service
public class TcmInventoryServiceImpl implements ITcmInventoryService
{
    @Autowired
    private TcmInventoryItemMapper inventoryMapper;

    @Autowired
    private ITcmHerbDictService herbDictService;

    @Autowired
    private TcmConsultationMapper consultationMapper;

    @Autowired
    private ITcmAuditLogService auditLogService;

    private static final String DATETIME_FORMAT = "yyyy-MM-dd HH:mm:ss";
    private static final ZoneId DEFAULT_ZONE = ZoneId.systemDefault();
    private static final DateTimeFormatter[] LOCAL_DATE_TIME_FORMATTERS = new DateTimeFormatter[] {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS") };

    @Override
    public List<TcmInventoryItem> selectTcmInventoryItemList(TcmInventoryItem item)
    {
        return inventoryMapper.selectTcmInventoryItemList(item);
    }

    @Override
    public List<TcmInventoryItem> selectTcmInventoryItemListIncludingDeleted(TcmInventoryItem item)
    {
        return inventoryMapper.selectTcmInventoryItemListIncludingDeleted(item);
    }

    @Override
    public TcmInventoryItem selectTcmInventoryItemById(String id)
    {
        return inventoryMapper.selectTcmInventoryItemById(id);
    }

    @Override
    public int insertTcmInventoryItem(TcmInventoryItem item)
    {
        normalizeInventoryCategory(item, true);
        normalizeRawHerbItem(item, null);
        if (item.getId() == null || item.getId().isEmpty())
        {
            item.setId(java.util.UUID.randomUUID().toString());
        }
        item.setCreateTime(DateUtils.getNowDate());
        return inventoryMapper.insertTcmInventoryItem(item);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateTcmInventoryItem(TcmInventoryItem item)
    {
        if (item == null || item.getId() == null || item.getId().trim().isEmpty())
        {
            throw new ServiceException("inventory item not found");
        }
        TcmInventoryItem existing = inventoryMapper.selectTcmInventoryItemForUpdate(item.getId());
        if (existing == null)
        {
            throw new ServiceException("inventory item not found");
        }
        mergeExistingForSparseUpdate(item, existing);
        normalizeInventoryCategory(item, false);
        normalizeRawHerbItem(item, existing);
        return inventoryMapper.updateTcmInventoryItem(item);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TcmInventoryItem softDeleteTcmInventoryItem(String id)
    {
        TcmInventoryItem item = inventoryMapper.selectTcmInventoryItemForUpdate(id);
        if (item == null)
        {
            throw new ServiceException("inventory item not found");
        }
        item.setDeletedAt(new SimpleDateFormat(DATETIME_FORMAT).format(new Date()));
        item.setIsActive(0);
        inventoryMapper.updateTcmInventoryItem(item);
        return item;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TcmInventoryItem restoreTcmInventoryItem(String id)
    {
        TcmInventoryItem item = inventoryMapper.selectTcmInventoryItemForUpdate(id);
        if (item == null)
        {
            throw new ServiceException("inventory item not found");
        }
        item.setDeletedAt(null);
        item.setIsActive(1);
        inventoryMapper.updateTcmInventoryItem(item);
        return item;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int hardDeleteTcmInventoryItem(String id)
    {
        TcmInventoryItem item = inventoryMapper.selectTcmInventoryItemForUpdate(id);
        if (item == null)
        {
            throw new ServiceException("inventory item not found");
        }
        if (item.getDeletedAt() == null || item.getDeletedAt().isEmpty())
        {
            throw new ServiceException("record must be soft deleted before physical deletion");
        }
        return inventoryMapper.deleteTcmInventoryItemById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TcmInventoryItem adjustStock(String id, BigDecimal delta)
    {
        TcmInventoryItem item = inventoryMapper.selectTcmInventoryItemForUpdate(id);
        if (item == null)
        {
            throw new ServiceException("inventory item not found");
        }
        BigDecimal currentQty = item.getQuantity() != null ? item.getQuantity() : BigDecimal.ZERO;
        BigDecimal newQty = currentQty.add(delta);
        if (newQty.compareTo(BigDecimal.ZERO) < 0)
        {
            throw new ServiceException("inventory quantity cannot be negative");
        }
        item.setQuantity(newQty);
        inventoryMapper.updateTcmInventoryItem(item);
        return item;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> deductFromPrescription(List<Map<String, Object>> herbals, String prescriptionType)
    {
        return changePrescriptionStock(herbals, prescriptionType, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> restoreFromPrescription(List<Map<String, Object>> herbals, String prescriptionType)
    {
        return changePrescriptionStock(herbals, prescriptionType, true);
    }

    private Map<String, Object> changePrescriptionStock(List<Map<String, Object>> herbals,
            String prescriptionType, boolean restore)
    {
        if (herbals == null || herbals.isEmpty() || "none".equals(prescriptionType))
        {
            return emptyResult();
        }
        String category = mapPrescriptionTypeToCategory(prescriptionType);
        List<Map<String, Object>> plan = new ArrayList<>();
        List<Map<String, Object>> notFound = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Map<String, TcmInventoryItem> lockedItems = new java.util.TreeMap<>();
        for (Map<String, Object> herbal : herbals)
        {
            BigDecimal quantity = readRequestedQuantity(herbal);
            if (quantity.signum() < 0)
            {
                errors.add("inventory quantity cannot be negative");
                continue;
            }
            if (quantity.signum() == 0) continue;
            String name = stringValue(herbal.get("name"));
            TcmInventoryItem item = resolveInventoryItem(stringValue(herbal.get("inventoryId")), name,
                    category, stringValue(herbal.get("supplierId")), restore);
            if (item == null)
            {
                notFound.add(new HashMap<>(herbal));
                errors.add(name + " inventory item not found");
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("inventoryId", item.getId());
            row.put("name", item.getName());
            row.put("quantity", quantity);
            row.put("prescriptionId", herbal.get("prescriptionId"));
            row.put("supplierId", item.getSupplierId());
            row.put("supplier", item.getSupplier());
            plan.add(row);
            lockedItems.put(item.getId(), item);
        }
        // Lock in a stable order and calculate against the current committed stock.
        // A repeated herb must consume its cumulative quantity, not overwrite an earlier deduction.
        Map<String, BigDecimal> balances = new HashMap<>();
        for (String id : lockedItems.keySet())
        {
            TcmInventoryItem item = inventoryMapper.selectTcmInventoryItemForUpdate(id);
            if (item == null || (!restore && ((item.getDeletedAt() != null && !item.getDeletedAt().isEmpty())
                    || !Integer.valueOf(1).equals(item.getIsActive()))))
            {
                errors.add("inventory item is no longer available: " + id);
                continue;
            }
            lockedItems.put(id, item);
            balances.put(id, item.getQuantity() != null ? item.getQuantity() : BigDecimal.ZERO);
        }
        for (Map<String, Object> row : plan)
        {
            String id = String.valueOf(row.get("inventoryId"));
            BigDecimal before = balances.get(id);
            if (before == null) continue;
            BigDecimal quantity = (BigDecimal) row.get("quantity");
            BigDecimal after = restore ? before.add(quantity) : before.subtract(quantity);
            row.put("currentQuantity", before);
            row.put("remainingQuantity", after);
            balances.put(id, after);
            if (after.signum() < 0)
            {
                errors.add(row.get("name") + " inventory is insufficient, current: " + before + ", requested: " + quantity);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", errors.isEmpty());
        result.put("errors", errors);
        result.put("notFound", notFound);
        result.put("warnings", new ArrayList<>());
        result.put("deducted", new ArrayList<>());
        if (!errors.isEmpty()) return result;

        String actorId;
        try { actorId = String.valueOf(SecurityUtils.getUserId()); }
        catch (Exception ignored) { actorId = "system"; }
        for (Map<String, Object> row : plan)
        {
            TcmInventoryItem item = lockedItems.get(String.valueOf(row.get("inventoryId")));
            item.setQuantity((BigDecimal) row.get("remainingQuantity"));
            inventoryMapper.updateTcmInventoryItem(item);
            String source = row.get("prescriptionId") == null ? "" : " [" + row.get("prescriptionId") + "]";
            auditLogService.log("inventory", item.getId(), item.getName(),
                    restore ? "PRESCRIPTION_RESTORE" : "PRESCRIPTION_DEDUCT", actorId,
                    (restore ? "Prescription stock restored" : "Prescription stock reserved") + source
                            + ": " + row.get("currentQuantity") + " -> " + row.get("remainingQuantity")
                            + " " + item.getUnit() + " (" + (restore ? "+" : "-") + row.get("quantity") + ")");
        }
        result.put("deducted", plan);
        return result;
    }

    @Override
    public List<TcmInventoryItem> selectByHerbDictId(String herbDictId)
    {
        return inventoryMapper.selectByHerbDictId(herbDictId);
    }

    @Override
    public Map<String, BigDecimal> calculateLast30DaysUsage(List<TcmInventoryItem> items)
    {
        Map<String, BigDecimal> usage = new HashMap<>();
        if (items == null || items.isEmpty())
        {
            return usage;
        }

        Map<String, TcmInventoryItem> inventoryById = new HashMap<>();
        Map<String, String> herbCategoryIndex = new HashMap<>();
        Map<String, String> herbCategorySupplierIndex = new HashMap<>();
        Map<String, String> nameCategoryIndex = new HashMap<>();
        Map<String, String> nameCategorySupplierIndex = new HashMap<>();
        for (TcmInventoryItem item : items)
        {
            if (item == null || item.getId() == null || item.getId().trim().isEmpty())
            {
                continue;
            }
            inventoryById.put(item.getId(), item);
            String category = normalizeKey(item.getCategory());
            String supplierId = normalizeKey(item.getSupplierId());
            if (item.getHerbDictId() != null && !item.getHerbDictId().trim().isEmpty())
            {
                herbCategoryIndex.putIfAbsent(buildCompoundKey(item.getHerbDictId(), category), item.getId());
                herbCategorySupplierIndex.putIfAbsent(buildCompoundKey(item.getHerbDictId(), category, supplierId), item.getId());
            }
            String itemName = normalizeKey(item.getName());
            if (!itemName.isEmpty())
            {
                nameCategoryIndex.putIfAbsent(buildCompoundKey(itemName, category), item.getId());
                nameCategorySupplierIndex.putIfAbsent(buildCompoundKey(itemName, category, supplierId), item.getId());
            }
        }

        Map<String, List<UsageEvent>> usageEvents = new HashMap<>();
        List<TcmConsultation> consultations = consultationMapper.selectTcmConsultationList(new TcmConsultation());
        for (TcmConsultation consultation : consultations)
        {
            LocalDateTime consultationDateTime = parseConsultationDateTime(consultation);
            if (consultationDateTime == null)
            {
                continue;
            }
            JSONObject payload = parsePayload(consultation.getPayload());
            for (Map<String, Object> prescription : toMapList(payload.get("prescriptions")))
            {
                if (isDeletedPrescription(prescription))
                {
                    continue;
                }
                if (!usesClinicInventory(prescription))
                {
                    continue;
                }
                String prescriptionType = stringValue(prescription.get("prescriptionType"));
                if ("none".equals(prescriptionType))
                {
                    continue;
                }
                String category = mapPrescriptionTypeToCategory(prescriptionType);
                List<Map<String, Object>> usageItems = toMapList(prescription.get("inventoryReservation"));
                if (usageItems.isEmpty())
                {
                    usageItems = buildUsageSnapshotFromPrescriptionItems(prescription, category);
                }
                for (Map<String, Object> usageItem : usageItems)
                {
                    BigDecimal quantity = readReservedUsageQuantity(usageItem);
                    if (quantity.compareTo(BigDecimal.ZERO) <= 0)
                    {
                        continue;
                    }
                    String inventoryId = resolveUsageInventoryId(
                            usageItem,
                            category,
                            inventoryById,
                            herbCategoryIndex,
                            herbCategorySupplierIndex,
                            nameCategoryIndex,
                            nameCategorySupplierIndex);
                    if (inventoryId == null || inventoryId.isEmpty())
                    {
                        continue;
                    }
                    usageEvents.computeIfAbsent(inventoryId, key -> new ArrayList<>())
                            .add(new UsageEvent(consultationDateTime, quantity));
                }
            }
        }
        for (Map.Entry<String, List<UsageEvent>> entry : usageEvents.entrySet())
        {
            usage.put(entry.getKey(), calculatePeakThirtyDayUsage(entry.getValue()));
        }
        return usage;
    }

    private BigDecimal calculatePeakThirtyDayUsage(List<UsageEvent> events)
    {
        if (events == null || events.isEmpty())
        {
            return BigDecimal.ZERO;
        }
        events.sort((left, right) -> left.dateTime.compareTo(right.dateTime));
        BigDecimal peak = BigDecimal.ZERO;
        BigDecimal windowTotal = BigDecimal.ZERO;
        int leftIndex = 0;
        for (int rightIndex = 0; rightIndex < events.size(); rightIndex++)
        {
            UsageEvent current = events.get(rightIndex);
            windowTotal = windowTotal.add(current.quantity);
            LocalDateTime windowStart = current.dateTime.minusDays(30);
            while (leftIndex <= rightIndex && events.get(leftIndex).dateTime.isBefore(windowStart))
            {
                windowTotal = windowTotal.subtract(events.get(leftIndex).quantity);
                leftIndex++;
            }
            if (windowTotal.compareTo(peak) > 0)
            {
                peak = windowTotal;
            }
        }
        return peak;
    }

    private boolean usesClinicInventory(Map<String, Object> prescription)
    {
        String prescriptionType = stringValue(prescription.get("prescriptionType"));
        if ("none".equals(prescriptionType))
        {
            return false;
        }
        String whereToGet = normalizeKey(stringValue(prescription.get("whereToGet")));
        return !whereToGet.contains("external") && !whereToGet.contains("外部");
    }

    private Map<String, Object> emptyResult()
    {
        Map<String, Object> empty = new HashMap<>();
        empty.put("success", true);
        empty.put("deducted", new ArrayList<>());
        empty.put("notFound", new ArrayList<>());
        empty.put("warnings", new ArrayList<>());
        return empty;
    }

    private BigDecimal readRequestedQuantity(Map<String, Object> herbal)
    {
        if (herbal.containsKey("convertedQty"))
        {
            BigDecimal convertedQty = toBigDecimal(herbal.get("convertedQty"));
            if (convertedQty.compareTo(BigDecimal.ZERO) > 0)
            {
                return convertedQty;
            }
        }
        if (herbal.containsKey("quantity"))
        {
            return toBigDecimal(herbal.get("quantity"));
        }
        return toBigDecimal(herbal.get("dosage"));
    }

    private BigDecimal readReservedUsageQuantity(Map<String, Object> usageItem)
    {
        if (usageItem == null)
        {
            return BigDecimal.ZERO;
        }
        if (usageItem.containsKey("reservedQty"))
        {
            BigDecimal reservedQty = toBigDecimal(usageItem.get("reservedQty"));
            if (reservedQty.compareTo(BigDecimal.ZERO) > 0)
            {
                return reservedQty;
            }
        }
        return readRequestedQuantity(usageItem);
    }

    private TcmInventoryItem resolveInventoryItem(String inventoryId, String name, String category,
            String preferredSupplierId, boolean restore)
    {
        if (inventoryId != null && !inventoryId.isEmpty() && !"null".equals(inventoryId))
        {
            TcmInventoryItem exact = inventoryMapper.selectTcmInventoryItemById(inventoryId);
            if (exact != null
                    && category.equals(exact.getCategory())
                    && (restore || ((exact.getDeletedAt() == null || exact.getDeletedAt().isEmpty())
                            && Integer.valueOf(1).equals(exact.getIsActive()))))
            {
                // Returning a recorded reservation must still work after this stock
                // entry was archived. Preserve its archived state and original ID.
                return exact;
            }
            // An explicit reservation belongs to this stock record, never a namesake.
            return null;
        }

        List<TcmInventoryItem> candidates = inventoryMapper.selectTcmInventoryItemsByName(name, category);
        if (candidates == null || candidates.isEmpty())
        {
            return null;
        }
        if (preferredSupplierId != null && !preferredSupplierId.isEmpty() && !"null".equals(preferredSupplierId))
        {
            for (TcmInventoryItem candidate : candidates)
            {
                if (preferredSupplierId.equals(candidate.getSupplierId()))
                {
                    return candidate;
                }
            }
        }
        return candidates.get(0);
    }

    private String resolveUsageInventoryId(
            Map<String, Object> usageItem,
            String category,
            Map<String, TcmInventoryItem> inventoryById,
            Map<String, String> herbCategoryIndex,
            Map<String, String> herbCategorySupplierIndex,
            Map<String, String> nameCategoryIndex,
            Map<String, String> nameCategorySupplierIndex)
    {
        String inventoryId = stringValue(usageItem.get("inventoryId"));
        if (inventoryId != null && inventoryById.containsKey(inventoryId))
        {
            return inventoryId;
        }
        String normalizedCategory = normalizeKey(category);
        String supplierId = normalizeKey(stringValue(usageItem.get("supplierId")));
        String herbDictId = stringValue(usageItem.get("herbDictId"));
        if (herbDictId != null && !herbDictId.trim().isEmpty())
        {
            String bySupplier = herbCategorySupplierIndex.get(buildCompoundKey(herbDictId, normalizedCategory, supplierId));
            if (bySupplier != null)
            {
                return bySupplier;
            }
            String byHerb = herbCategoryIndex.get(buildCompoundKey(herbDictId, normalizedCategory));
            if (byHerb != null)
            {
                return byHerb;
            }
        }
        String name = stringValue(usageItem.get("name"));
        if (name != null && !name.trim().isEmpty())
        {
            String bySupplier = nameCategorySupplierIndex.get(buildCompoundKey(name, normalizedCategory, supplierId));
            if (bySupplier != null)
            {
                return bySupplier;
            }
            return nameCategoryIndex.get(buildCompoundKey(name, normalizedCategory));
        }
        return null;
    }

    private List<Map<String, Object>> buildUsageSnapshotFromPrescriptionItems(Map<String, Object> prescription, String category)
    {
        List<Map<String, Object>> items = toMapList(prescription.get("items"));
        if (items.isEmpty())
        {
            return new ArrayList<>();
        }
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        BigDecimal prescriptionQuantity = toBigDecimal(prescription.get("quantity"));
        if (prescriptionQuantity.compareTo(BigDecimal.ZERO) <= 0)
        {
            prescriptionQuantity = BigDecimal.ONE;
        }
        for (Map<String, Object> item : items)
        {
            String name = stringValue(item.get("name"));
            if (name == null || name.trim().isEmpty())
            {
                continue;
            }
            String inventoryId = stringValue(item.get("inventoryId"));
            String herbDictId = stringValue(item.get("herbDictId"));
            String supplierId = stringValue(item.get("supplierId"));
            String key = buildCompoundKey(inventoryId, herbDictId, name, category, supplierId);
            Map<String, Object> mergedItem = merged.get(key);
            if (mergedItem == null)
            {
                mergedItem = new LinkedHashMap<>();
                putIfNotBlank(mergedItem, "inventoryId", inventoryId);
                putIfNotBlank(mergedItem, "herbDictId", herbDictId);
                putIfNotBlank(mergedItem, "supplierId", supplierId);
                mergedItem.put("name", name);
                mergedItem.put("quantity", BigDecimal.ZERO);
                merged.put(key, mergedItem);
            }
            // Converted and legacy item quantities are totals; dosage is per dose.
            BigDecimal requestedQty = toBigDecimal(item.get("convertedQty"));
            if (requestedQty.compareTo(BigDecimal.ZERO) <= 0)
            {
                requestedQty = toBigDecimal(item.get("quantity"));
            }
            if (requestedQty.compareTo(BigDecimal.ZERO) <= 0)
            {
                requestedQty = toBigDecimal(item.get("dosage")).multiply(prescriptionQuantity);
            }
            mergedItem.put("quantity", toBigDecimal(mergedItem.get("quantity")).add(requestedQty));
        }
        return new ArrayList<>(merged.values());
    }

    private boolean isWithinLast30Days(TcmConsultation consultation, LocalDateTime cutoff)
    {
        LocalDateTime consultationDateTime = parseConsultationDateTime(consultation);
        return consultationDateTime != null && !consultationDateTime.isBefore(cutoff);
    }

    private static final class UsageEvent
    {
        private final LocalDateTime dateTime;
        private final BigDecimal quantity;

        private UsageEvent(LocalDateTime dateTime, BigDecimal quantity)
        {
            this.dateTime = dateTime;
            this.quantity = quantity == null ? BigDecimal.ZERO : quantity;
        }
    }

    private LocalDateTime parseConsultationDateTime(TcmConsultation consultation)
    {
        if (consultation == null)
        {
            return null;
        }
        LocalDateTime consultDate = parseDateTimeValue(consultation.getConsultDate());
        if (consultDate != null)
        {
            return consultDate;
        }
        Date createTime = consultation.getCreateTime();
        if (createTime != null)
        {
            return LocalDateTime.ofInstant(createTime.toInstant(), DEFAULT_ZONE);
        }
        return null;
    }

    private LocalDateTime parseDateTimeValue(Object value)
    {
        if (value == null)
        {
            return null;
        }
        if (value instanceof Date)
        {
            return LocalDateTime.ofInstant(((Date) value).toInstant(), DEFAULT_ZONE);
        }
        if (value instanceof LocalDateTime)
        {
            return (LocalDateTime) value;
        }
        if (value instanceof LocalDate)
        {
            return LocalDateTime.of((LocalDate) value, LocalTime.MIN);
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty())
        {
            return null;
        }
        try
        {
            return OffsetDateTime.parse(text).toLocalDateTime();
        }
        catch (DateTimeParseException ignored)
        {
        }
        for (DateTimeFormatter formatter : LOCAL_DATE_TIME_FORMATTERS)
        {
            try
            {
                return LocalDateTime.parse(text, formatter);
            }
            catch (DateTimeParseException ignored)
            {
            }
        }
        try
        {
            return LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay();
        }
        catch (DateTimeParseException ignored)
        {
        }
        return null;
    }

    private JSONObject parsePayload(String payload)
    {
        try
        {
            if (payload == null || payload.trim().isEmpty())
            {
                return new JSONObject();
            }
            JSONObject parsed = JSON.parseObject(payload);
            return parsed != null ? parsed : new JSONObject();
        }
        catch (Exception e)
        {
            return new JSONObject();
        }
    }

    private List<Map<String, Object>> toMapList(Object value)
    {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!(value instanceof List<?>))
        {
            return result;
        }
        for (Object item : (List<?>) value)
        {
            if (item instanceof Map<?, ?>)
            {
                @SuppressWarnings("unchecked")
                Map<String, Object> mapped = new LinkedHashMap<>((Map<String, Object>) item);
                result.add(mapped);
            }
        }
        return result;
    }

    private boolean isDeletedPrescription(Map<String, Object> prescription)
    {
        String deletedAt = stringValue(prescription.get("deletedAt"));
        if (deletedAt != null && !deletedAt.trim().isEmpty())
        {
            return true;
        }
        String rxStatus = stringValue(prescription.get("rxStatus"));
        return "deleted".equalsIgnoreCase(rxStatus) || Boolean.TRUE.equals(prescription.get("deleted"));
    }

    private String mapPrescriptionTypeToCategory(String prescriptionType)
    {
        if (prescriptionType == null)
        {
            return "raw_herbs";
        }
        switch (prescriptionType)
        {
            case "raw_herbs":
                return "raw_herbs";
            case "powder":
                return "powder";
            case "pills":
                return "pills";
            default:
                return "raw_herbs";
        }
    }

    private void normalizeInventoryCategory(TcmInventoryItem item, boolean defaultBlankToRaw)
    {
        if (item == null)
        {
            return;
        }
        item.setCategory(normalizeInventoryCategory(item.getCategory(), defaultBlankToRaw ? "raw_herbs" : null));
    }

    private String normalizeInventoryCategory(String category)
    {
        return normalizeInventoryCategory(category, "raw_herbs");
    }

    static String normalizeInventoryCategory(String category, String blankValue)
    {
        if (category == null || category.trim().isEmpty())
        {
            return blankValue;
        }
        String normalized = category.trim().toLowerCase()
                .replace("-", "_")
                .replace(" ", "_");
        String compact = normalized.replace("_", "");
        if ("raw_herbs".equals(normalized)
                || "rawherbs".equals(compact)
                || "rawherb".equals(compact)
                || "herbs".equals(compact)
                || "herb".equals(compact)
                || normalized.contains("草药")
                || normalized.contains("中药")
                || normalized.contains("饮片"))
        {
            return "raw_herbs";
        }
        if ("powder".equals(normalized)
                || "powders".equals(compact)
                || "granule".equals(compact)
                || "granules".equals(compact)
                || normalized.contains("颗粒")
                || normalized.contains("粉"))
        {
            return "powder";
        }
        if ("pills".equals(normalized)
                || "pill".equals(compact)
                || "patentmedicine".equals(compact)
                || "patentmedicines".equals(compact)
                || normalized.contains("成药")
                || normalized.contains("丸")
                || normalized.contains("片"))
        {
            return "pills";
        }
        return normalized;
    }

    private BigDecimal toBigDecimal(Object obj)
    {
        if (obj == null)
        {
            return BigDecimal.ZERO;
        }
        if (obj instanceof BigDecimal)
        {
            return (BigDecimal) obj;
        }
        if (obj instanceof Number)
        {
            return new BigDecimal(obj.toString());
        }
        try
        {
            return new BigDecimal(obj.toString());
        }
        catch (NumberFormatException e)
        {
            return BigDecimal.ZERO;
        }
    }

    private String stringValue(Object value)
    {
        return value == null ? null : String.valueOf(value);
    }

    private String normalizeKey(String value)
    {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private String buildCompoundKey(Object... parts)
    {
        StringBuilder builder = new StringBuilder();
        for (Object part : parts)
        {
            if (builder.length() > 0)
            {
                builder.append("::");
            }
            builder.append(normalizeKey(part == null ? null : String.valueOf(part)));
        }
        return builder.toString();
    }

    private void putIfNotBlank(Map<String, Object> target, String key, String value)
    {
        if (value != null && !value.trim().isEmpty())
        {
            target.put(key, value);
        }
    }

    private void mergeExistingForSparseUpdate(TcmInventoryItem item, TcmInventoryItem existing)
    {
        item.setDeletedAt(existing.getDeletedAt());
        // Sparse edits retain existing metadata; supplied invoice costs must also survive.
        JSONObject requestedPayload = parsePayload(item.getPayload());
        if (requestedPayload.isEmpty())
        {
            item.setPayload(existing.getPayload());
        }
        else
        {
            JSONObject payload = parsePayload(existing.getPayload());
            payload.putAll(requestedPayload);
            item.setPayload(payload.toJSONString());
        }
        item.setIsActive(existing.getIsActive());
        item.setCategory(mergeValue(item.getCategory(), existing.getCategory()));
        item.setHerbDictId(mergeValue(item.getHerbDictId(), existing.getHerbDictId()));
        item.setName(mergeValue(item.getName(), existing.getName()));
        item.setSupplierId(mergeValue(item.getSupplierId(), existing.getSupplierId()));
        item.setSupplier(mergeValue(item.getSupplier(), existing.getSupplier()));
        item.setUnit(mergeValue(item.getUnit(), existing.getUnit()));
        item.setBranchId(mergeValue(item.getBranchId(), existing.getBranchId()));
    }

    private void normalizeRawHerbItem(TcmInventoryItem item, TcmInventoryItem existing)
    {
        if (item == null)
        {
            return;
        }
        String category = mergeValue(item.getCategory(), existing != null ? existing.getCategory() : null);
        if (!"raw_herbs".equals(category))
        {
            return;
        }

        String herbDictId = mergeValue(item.getHerbDictId(), existing != null ? existing.getHerbDictId() : null);
        if (herbDictId == null || herbDictId.trim().isEmpty())
        {
            throw new ServiceException("raw herb herbDictId is required");
        }

        TcmHerbDict herb = requireActiveHerbDict(herbDictId);
        item.setHerbDictId(herb.getId());
        item.setName(herb.getName());
    }

    private TcmHerbDict requireActiveHerbDict(String herbDictId)
    {
        TcmHerbDict herb = herbDictService.selectTcmHerbDictById(herbDictId);
        if (herb == null
                || herb.getIsActive() == null
                || herb.getIsActive() != 1
                || (herb.getDeletedAt() != null && !herb.getDeletedAt().trim().isEmpty()))
        {
            throw new ServiceException("herb dictionary entry is invalid or inactive");
        }
        return herb;
    }

    private String mergeValue(String requestValue, String existingValue)
    {
        if (requestValue != null && !requestValue.trim().isEmpty())
        {
            return requestValue;
        }
        return existingValue;
    }
}
