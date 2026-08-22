package com.mrrorigin.revenue;

import static java.math.RoundingMode.HALF_UP;
import com.mrrorigin.revenue.RevenueModels.*;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class RevenueCalculationService {
 public static final String CALCULATION_VERSION="mrr-v1";
 private static final Set<String> ZERO=Set.of("trialing","incomplete","incomplete_expired","unpaid","paused","canceled");
 // P6 observability slice (#28, review fix): invocation-level result is a small bounded enum
 // (success/failure) counted once per recordAndReplay call -- never once per historical snapshot
 // replay() happens to touch. Per-snapshot supported/unsupported counts are NOT counters: replay()
 // rebuilds a customer's *entire* history on every call (see its Javadoc), so a counter incremented
 // inside saveSnapshot/saveUnsupported would recount every old, unchanged historical instant on
 // every unrelated later state for the same customer -- unusable for an alert claiming a *new*
 // unsupported pattern appeared. RevenueCalculationSnapshotMetrics instead exposes the CURRENT
 // persisted supported/unsupported snapshot counts as DB-backed gauges, immune to replay-count
 // inflation because a gauge reports what's true right now, not how many times it was recomputed.
 private static final String INVOCATIONS_METRIC="mrrorigin.revenue.calculation.invocations";
 private static final String DURATION_METRIC="mrrorigin.revenue.calculation.duration";
 private final JdbcClient db;
 private final MeterRegistry meterRegistry;
 // Pre-registered at startup (rather than created lazily on first increment) so these report an
 // explicit 0, not simply absent, until something actually happens -- standard Prometheus counter
 // practice.
 private final Counter invocationSuccessCounter;
 private final Counter invocationFailureCounter;
 private final Timer durationTimer;
 public RevenueCalculationService(JdbcClient db,MeterRegistry meterRegistry){
  this.db=db;
  this.meterRegistry=meterRegistry;
  this.invocationSuccessCounter=Counter.builder(INVOCATIONS_METRIC).tag("result","success").register(meterRegistry);
  this.invocationFailureCounter=Counter.builder(INVOCATIONS_METRIC).tag("result","failure").register(meterRegistry);
  this.durationTimer=Timer.builder(DURATION_METRIC).register(meterRegistry);
 }
 @Transactional public void recordAndReplay(SubscriptionState state){recordAndReplay(List.of(state));}
 @Transactional public void recordAndReplay(List<SubscriptionState> states){
  Timer.Sample sample=Timer.start(meterRegistry);
  try{
   recordAndReplayTimed(states);
   // Deferred to after-commit, once per invocation -- not once per historical snapshot -- so this
   // never inflates with replay() (see the class-level metrics comment above).
   afterCommit(invocationSuccessCounter::increment);
  }
  catch(RuntimeException failure){invocationFailureCounter.increment();throw failure;}
  finally{sample.stop(durationTimer);}
 }
 private void recordAndReplayTimed(List<SubscriptionState> states){
  if(states.isEmpty())return; UUID w=states.getFirst().workspaceId();String c=states.getFirst().customerId();
  if(w==null||c==null||c.isBlank())throw new IllegalArgumentException("workspace and customer required");
  for(var s:states)if(!w.equals(s.workspaceId())||!c.equals(s.customerId()))throw new IllegalArgumentException("one tenant/customer per batch");
  lockCustomer(w,c);for(var s:states)insert(s);replay(w,c);
 }
 public List<Snapshot> snapshots(UUID w,String c){return db.sql("SELECT stripe_customer_id,currency,amount_minor,effective_at,supported,unsupported_reason,source_billing_references FROM customer_mrr_snapshots WHERE workspace_id=:w AND stripe_customer_id=:c AND calculation_version=:v ORDER BY effective_at,currency NULLS FIRST").param("w",w).param("c",c).param("v",CALCULATION_VERSION).query((r,n)->new Snapshot(r.getString(1),r.getString(2),(Long)r.getObject(3),r.getObject(4,OffsetDateTime.class),r.getBoolean(5),r.getString(6),List.of((String[])r.getArray(7).getArray()))).list();}
 public List<Movement> movements(UUID w,String c){return db.sql("SELECT stripe_customer_id,currency,amount_minor,movement_type,effective_at,source_billing_references FROM customer_mrr_movements WHERE workspace_id=:w AND stripe_customer_id=:c AND calculation_version=:v ORDER BY effective_at,currency").param("w",w).param("c",c).param("v",CALCULATION_VERSION).query((r,n)->new Movement(r.getString(1),r.getString(2),r.getLong(3),r.getString(4),r.getObject(5,OffsetDateTime.class),List.of((String[])r.getArray(6).getArray()))).list();}
 private void insert(SubscriptionState s){
  required(s.subscriptionId());required(s.status());required(s.sourceBillingReference());if(s.effectiveAt()==null)throw new IllegalArgumentException(UnsupportedReason.UNKNOWN_EFFECTIVE_AT.name());UUID id=UUID.randomUUID();
  int n=db.sql("INSERT INTO revenue_subscription_states(id,workspace_id,stripe_customer_id,stripe_subscription_id,effective_at,status,source_billing_reference) VALUES(:id,:w,:c,:sub,:at,:status,:ref) ON CONFLICT(workspace_id,source_billing_reference) DO NOTHING").param("id",id).param("w",s.workspaceId()).param("c",s.customerId()).param("sub",s.subscriptionId()).param("at",s.effectiveAt()).param("status",s.status()).param("ref",s.sourceBillingReference()).update();if(n==0)return;
  for(var i:s.items()){required(i.sourceReference());db.sql("INSERT INTO revenue_subscription_state_items VALUES(:id,:w,:s,:ref,:cur,:amt,:qty,:int,:cnt,:usage)").param("id",UUID.randomUUID()).param("w",s.workspaceId()).param("s",id).param("ref",i.sourceReference()).param("cur",i.currency()).param("amt",i.unitAmountMinor()).param("qty",i.quantity()).param("int",i.interval()).param("cnt",i.intervalCount()).param("usage",i.usagePricing()).update();}
  for(var d:s.discounts()){required(d.sourceReference());db.sql("INSERT INTO revenue_subscription_state_discounts(id,workspace_id,state_id,source_discount_reference,source_item_reference,percent_off,amount_off_minor,currency,start_at,end_at,customer_level) VALUES(:id,:w,:s,:ref,:item,:pct,:amt,:cur,:start,:end,:customer)").param("id",UUID.randomUUID()).param("w",s.workspaceId()).param("s",id).param("ref",d.sourceReference()).param("item",d.itemReference()).param("pct",d.percentOff()).param("amt",d.amountOffMinor()).param("cur",d.currency()).param("start",d.startAt()).param("end",d.endAt()).param("customer",d.customerLevel()).update();}
 }
 private void lockCustomer(UUID w,String c){db.sql("SELECT pg_advisory_xact_lock(hashtext(:w),hashtext(:c))").param("w",w.toString()).param("c",c).query((r,n)->0).single();}
 // Runs `action` only if this method's surrounding @Transactional call actually commits (P6
 // observability slice, #28, review fix): a Micrometer increment made before that method returns
 // is not itself part of the DB transaction, so an eager increment would survive a later rollback
 // within the same call and over-report work that was never durably persisted.
 private static void afterCommit(Runnable action){TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){action.run();}});}
 private void replay(UUID w,String c){
  var all=history(w,c);db.sql("DELETE FROM customer_mrr_movements WHERE workspace_id=:w AND stripe_customer_id=:c AND calculation_version=:v").param("w",w).param("c",c).param("v",CALCULATION_VERSION).update();db.sql("DELETE FROM customer_mrr_snapshots WHERE workspace_id=:w AND stripe_customer_id=:c AND calculation_version=:v").param("w",w).param("c",c).param("v",CALCULATION_VERSION).update();
  Map<String,State> current=new HashMap<>();Map<String,Long> before=new TreeMap<>();Set<String> ever=new HashSet<>();int p=0;
  while(p<all.size()){var at=all.get(p).at;List<String> changed=new ArrayList<>();while(p<all.size()&&all.get(p).at.equals(at)){var x=all.get(p++);current.put(x.subscription,x);changed.add(x.ref);}var calc=calculate(current.values(),at);if(calc.reason!=null){saveUnsupported(w,c,at,calc.reason,calc.refs);continue;}Set<String> currencies=new TreeSet<>(before.keySet());currencies.addAll(calc.amounts.keySet());for(var cur:currencies){long old=before.getOrDefault(cur,0L),now=calc.amounts.getOrDefault(cur,0L);saveSnapshot(w,c,cur,now,at,calc.refs);if(old!=now){String type=old==0?(ever.contains(cur)?"REACTIVATION":"NEW"):now==0?"CHURN":now>old?"EXPANSION":"CONTRACTION";saveMovement(w,c,cur,Math.abs(now-old),type,at,changed);}if(now>0)ever.add(cur);}before=new TreeMap<>(calc.amounts);}
 }
 private Calc calculate(Collection<State> states,OffsetDateTime at){Map<String,Long> totals=new TreeMap<>();List<String> refs=states.stream().map(State::ref).sorted().toList();try{long paid=states.stream().filter(s->Set.of("active","past_due").contains(s.status)).count();if(paid>1&&states.stream().flatMap(s->s.discounts.stream()).anyMatch(d->d.customerLevel()&&d.amountOffMinor()!=null&&active(d,at)))throw bad(UnsupportedReason.AMBIGUOUS_FIXED_DISCOUNT_ALLOCATION);for(var s:states){if(ZERO.contains(s.status)){addZeroCurrencies(s,totals);continue;}var a=normalize(s,at);totals.merge(a.currency,a.value,Math::addExact);}return new Calc(totals,null,refs);}catch(Bad b){return new Calc(Map.of(),b.reason,refs);}}
 private static boolean active(Discount d,OffsetDateTime at){return(d.startAt()==null||!d.startAt().isAfter(at))&&(d.endAt()==null||d.endAt().isAfter(at));}
 private void addZeroCurrencies(State s,Map<String,Long> totals){String currency=null;for(var i:s.items){if(i.currency()==null||!i.currency().matches("[A-Z]{3}"))throw bad(UnsupportedReason.UNKNOWN_CURRENCY);if(currency!=null&&!currency.equals(i.currency()))throw bad(UnsupportedReason.MIXED_CURRENCY_SUBSCRIPTION);currency=i.currency();}if(currency!=null)totals.putIfAbsent(currency,0L);}
 private Amount normalize(State s,OffsetDateTime at){
  if(!Set.of("active","past_due").contains(s.status))throw bad(UnsupportedReason.UNSUPPORTED_INTERVAL);if(s.items.isEmpty())throw bad(UnsupportedReason.UNKNOWN_CURRENCY);
  Set<String> currencies=new HashSet<>();BigDecimal sum=BigDecimal.ZERO;
  var discounts=s.discounts.stream().filter(d->active(d,at)).toList();
  if(discounts.size()>1)throw bad(UnsupportedReason.UNSUPPORTED_DISCOUNT);
  for(var d:discounts){boolean percent=d.percentOff()!=null,amount=d.amountOffMinor()!=null;if(percent==amount||d.startAt()==null||d.endAt()==null)throw bad(UnsupportedReason.UNSUPPORTED_DISCOUNT);if(d.itemReference()!=null&&s.items.stream().noneMatch(i->d.itemReference().equals(i.sourceReference())))throw bad(UnsupportedReason.UNSUPPORTED_DISCOUNT);if(percent&&(d.percentOff().signum()<0||d.percentOff().compareTo(BigDecimal.valueOf(100))>0))throw bad(UnsupportedReason.UNSUPPORTED_DISCOUNT);if(amount&&d.amountOffMinor()<0)throw bad(UnsupportedReason.UNSUPPORTED_DISCOUNT);}
  for(var i:s.items){if(i.currency()==null||!i.currency().matches("[A-Z]{3}"))throw bad(UnsupportedReason.UNKNOWN_CURRENCY);currencies.add(i.currency());if(currencies.size()>1)throw bad(UnsupportedReason.MIXED_CURRENCY_SUBSCRIPTION);if(i.usagePricing())throw bad(UnsupportedReason.UNSUPPORTED_USAGE_PRICING);if(i.quantity()==null||i.quantity().stripTrailingZeros().scale()>0||i.quantity().signum()<=0)throw bad(UnsupportedReason.UNSUPPORTED_QUANTITY);if(i.unitAmountMinor()==null||i.intervalCount()==null||i.intervalCount()<=0||!Set.of("month","year").contains(i.interval()))throw bad(UnsupportedReason.UNSUPPORTED_INTERVAL);BigDecimal amount=BigDecimal.valueOf(i.unitAmountMinor()).multiply(i.quantity());for(var d:discounts){if(d.itemReference()!=null&&!d.itemReference().equals(i.sourceReference()))continue;if(d.percentOff()!=null)amount=amount.multiply(BigDecimal.ONE.subtract(d.percentOff().movePointLeft(2)));else{if(!Objects.equals(d.currency(),i.currency()))throw bad(UnsupportedReason.DISCOUNT_CURRENCY_MISMATCH);if(s.items.size()>1&&d.itemReference()==null)throw bad(UnsupportedReason.AMBIGUOUS_FIXED_DISCOUNT_ALLOCATION);amount=amount.subtract(BigDecimal.valueOf(d.amountOffMinor()));}}long months=i.interval().equals("year")?Math.multiplyExact(12L,i.intervalCount()):i.intervalCount();sum=sum.add(amount.max(BigDecimal.ZERO).divide(BigDecimal.valueOf(months),20,HALF_UP));}return new Amount(currencies.iterator().next(),sum.setScale(0,HALF_UP).longValueExact());
 }
 private List<State> history(UUID w,String c){var rows=db.sql("SELECT id,stripe_subscription_id,effective_at,status,source_billing_reference FROM revenue_subscription_states WHERE workspace_id=:w AND stripe_customer_id=:c ORDER BY effective_at,stripe_subscription_id").param("w",w).param("c",c).query((r,n)->new State((UUID)r.getObject(1),r.getString(2),r.getObject(3,OffsetDateTime.class),r.getString(4),r.getString(5),new ArrayList<>(),new ArrayList<>())).list();for(var x:rows){x.items.addAll(db.sql("SELECT source_item_reference,currency,unit_amount_minor,quantity,recurring_interval,interval_count,usage_pricing FROM revenue_subscription_state_items WHERE workspace_id=:w AND state_id=:s ORDER BY source_item_reference").param("w",w).param("s",x.id).query((r,n)->new Item(r.getString(1),r.getString(2),(Long)r.getObject(3),r.getBigDecimal(4),r.getString(5),(Integer)r.getObject(6),r.getBoolean(7))).list());x.discounts.addAll(db.sql("SELECT source_discount_reference,source_item_reference,percent_off,amount_off_minor,currency,start_at,end_at,customer_level FROM revenue_subscription_state_discounts WHERE workspace_id=:w AND state_id=:s ORDER BY source_discount_reference").param("w",w).param("s",x.id).query((r,n)->new Discount(r.getString(1),r.getString(2),r.getBigDecimal(3),(Long)r.getObject(4),r.getString(5),r.getObject(6,OffsetDateTime.class),r.getObject(7,OffsetDateTime.class),r.getBoolean(8))).list());}return rows;}
 private void saveSnapshot(UUID w,String c,String cur,long amt,OffsetDateTime at,List<String> refs){db.sql("INSERT INTO customer_mrr_snapshots(id,workspace_id,stripe_customer_id,currency,amount_minor,effective_at,calculation_version,supported,source_billing_references) VALUES(:id,:w,:c,:cur,:amt,:at,:v,true,:refs)").param("id",UUID.randomUUID()).param("w",w).param("c",c).param("cur",cur).param("amt",amt).param("at",at).param("v",CALCULATION_VERSION).param("refs",refs.toArray(String[]::new)).update();}
 private void saveUnsupported(UUID w,String c,OffsetDateTime at,UnsupportedReason reason,List<String> refs){db.sql("INSERT INTO customer_mrr_snapshots(id,workspace_id,stripe_customer_id,effective_at,calculation_version,supported,unsupported_reason,source_billing_references) VALUES(:id,:w,:c,:at,:v,false,:r,:refs)").param("id",UUID.randomUUID()).param("w",w).param("c",c).param("at",at).param("v",CALCULATION_VERSION).param("r",reason.name()).param("refs",refs.toArray(String[]::new)).update();}
 private void saveMovement(UUID w,String c,String cur,long amt,String type,OffsetDateTime at,List<String> refs){db.sql("INSERT INTO customer_mrr_movements(id,workspace_id,stripe_customer_id,currency,amount_minor,movement_type,effective_at,calculation_version,source_billing_references) VALUES(:id,:w,:c,:cur,:amt,:type,:at,:v,:refs)").param("id",UUID.randomUUID()).param("w",w).param("c",c).param("cur",cur).param("amt",amt).param("type",type).param("at",at).param("v",CALCULATION_VERSION).param("refs",refs.toArray(String[]::new)).update();}
 private static void required(String s){if(s==null||s.isBlank())throw new IllegalArgumentException("required value missing");}private static Bad bad(UnsupportedReason r){return new Bad(r);}private record Amount(String currency,long value){}private record Calc(Map<String,Long> amounts,UnsupportedReason reason,List<String> refs){}private record State(UUID id,String subscription,OffsetDateTime at,String status,String ref,List<Item> items,List<Discount> discounts){}private static final class Bad extends RuntimeException{final UnsupportedReason reason;Bad(UnsupportedReason r){reason=r;}}
}
