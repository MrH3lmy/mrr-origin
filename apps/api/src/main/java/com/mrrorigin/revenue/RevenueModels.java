package com.mrrorigin.revenue;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
public final class RevenueModels {
 private RevenueModels() {}
 public record Item(String sourceReference,String currency,Long unitAmountMinor,BigDecimal quantity,String interval,Integer intervalCount,boolean usagePricing) {}
 public record Discount(String sourceReference,String itemReference,BigDecimal percentOff,Long amountOffMinor,String currency,OffsetDateTime startAt,OffsetDateTime endAt) {}
 public record SubscriptionState(UUID workspaceId,String customerId,String subscriptionId,OffsetDateTime effectiveAt,String status,String sourceBillingReference,List<Item> items,List<Discount> discounts) {
  public SubscriptionState { items=items==null?List.of():List.copyOf(items); discounts=discounts==null?List.of():List.copyOf(discounts); }
 }
 public record Snapshot(String customerId,String currency,Long amountMinor,OffsetDateTime effectiveAt,boolean supported,String unsupportedReason,List<String> sourceBillingReferences) {}
 public record Movement(String customerId,String currency,long amountMinor,String type,OffsetDateTime effectiveAt,List<String> sourceBillingReferences) {}
}
