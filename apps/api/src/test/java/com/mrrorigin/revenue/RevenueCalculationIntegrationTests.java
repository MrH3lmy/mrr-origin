package com.mrrorigin.revenue;

import static com.mrrorigin.revenue.RevenueModels.*;
import static org.assertj.core.api.Assertions.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest @Testcontainers
class RevenueCalculationIntegrationTests {
 @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:18-alpine");
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);}
 @Autowired RevenueCalculationService service; @Autowired JdbcClient jdbc; UUID w; static JsonNode golden;
 @BeforeAll static void loadGoldenFixture() throws IOException{try(var input=RevenueCalculationIntegrationTests.class.getResourceAsStream("/golden/mrr-v1.json")){assertThat(input).isNotNull();golden=new ObjectMapper().readTree(input);}}
 @BeforeEach void setup(){jdbc.sql("TRUNCATE workspaces CASCADE").update();w=UUID.randomUUID();jdbc.sql("INSERT INTO workspaces(id,name,slug,reporting_currency) VALUES(:id,'test',:slug,'USD')").param("id",w).param("slug","w-"+w).update();}
 private Item item(String ref,String currency,long amount,int qty,String interval,int count){return new Item(ref,currency,amount,BigDecimal.valueOf(qty),interval,count,false);}
 private SubscriptionState state(String customer,String sub,String at,String status,String ref,Item... items){return new SubscriptionState(w,customer,sub,OffsetDateTime.parse(at),status,ref,List.of(items),List.of());}
 @Test void movementsReplayOutOfOrderAndDuplicateWithoutDrift(){
  var first=state("cus","sub","2026-01-01T00:00:00Z","active","evt-1",item("si","USD",12000,1,"year",1));
  var expand=state("cus","sub","2026-02-01T00:00:00Z","active","evt-2",item("si","USD",12000,2,"year",1));
  var churn=state("cus","sub","2026-03-01T00:00:00Z","canceled","evt-3",item("si","USD",12000,2,"year",1));
  var reactivate=state("cus","sub","2026-04-01T00:00:00Z","active","evt-4",item("si","USD",12000,1,"year",1));
  service.recordAndReplay(List.of(churn,first,reactivate,expand));service.recordAndReplay(first);
  assertThat(service.movements(w,"cus")).extracting(Movement::type).containsExactly("NEW","EXPANSION","CHURN","REACTIVATION");
  assertThat(service.movements(w,"cus")).extracting(Movement::amountMinor).containsExactly(1000L,1000L,2000L,1000L);
  assertThat(jdbc.sql("SELECT count(*) FROM revenue_subscription_states WHERE workspace_id=:w").param("w",w).query(Long.class).single()).isEqualTo(4);
 }
 @Test void netsSameTimestampAcrossSubscriptionsAndRoundsAtSubscriptionBoundary(){
  service.recordAndReplay(List.of(state("cus","a","2026-01-01T00:00:00Z","active","a1",item("a","USD",1000,1,"year",1),item("a2","USD",1000,1,"year",1)),state("cus","b","2026-01-01T00:00:00Z","active","b1",item("b","USD",333,1,"month",1))));
  service.recordAndReplay(List.of(state("cus","a","2026-02-01T00:00:00Z","canceled","a2",item("a","USD",1000,1,"year",1)),state("cus","b","2026-02-01T00:00:00Z","active","b2",item("b","USD",500,1,"month",1))));
  assertThat(service.snapshots(w,"cus")).extracting(Snapshot::amountMinor).containsExactly(500L,500L);
  assertThat(service.movements(w,"cus")).hasSize(1).first().extracting(Movement::type).isEqualTo("NEW");
 }
 @Test void currenciesAreIndependentAndUnsupportedIsVisibleAndTenantScoped(){
  service.recordAndReplay(List.of(state("cus","usd","2026-01-01T00:00:00Z","active","u1",item("u","USD",2000,1,"month",1)),state("cus","eur","2026-02-01T00:00:00Z","active","e1",item("e","EUR",1800,1,"month",1)),state("cus","usd","2026-02-01T00:00:00Z","canceled","u2",item("u","USD",2000,1,"month",1))));
  assertThat(service.movements(w,"cus")).extracting(Movement::currency,Movement::type).contains(tuple("USD","NEW"),tuple("EUR","NEW"),tuple("USD","CHURN"));
  service.recordAndReplay(state("bad","bad","2026-01-01T00:00:00Z","active","bad1",item("x",null,100,1,"month",1)));
  assertThat(service.snapshots(w,"bad")).singleElement().satisfies(s->{assertThat(s.supported()).isFalse();assertThat(s.unsupportedReason()).isEqualTo("UNKNOWN_CURRENCY");});
  UUID other=UUID.randomUUID();jdbc.sql("INSERT INTO workspaces(id,name,slug,reporting_currency) VALUES(:id,'other',:slug,'USD')").param("id",other).param("slug","w-"+other).update();assertThat(service.snapshots(other,"cus")).isEmpty();
 }
 @Test void approvedGoldenSnapshotsExecuteAgainstTheProductionEngine(){
  int index=0;for(JsonNode testCase:golden.path("snapshotCases")){String id=testCase.path("id").asText(),customer="snapshot-"+index++;OffsetDateTime at=OffsetDateTime.parse("2026-07-01T00:00:00Z");String currency=testCase.path("currency").asText(golden.path("currency").asText());List<Item> items=new ArrayList<>();if(testCase.has("items")){int itemIndex=0;for(JsonNode node:testCase.path("items"))items.add(new Item("item-"+itemIndex++,currency,node.path("periodAmount").asLong(),BigDecimal.valueOf(node.path("quantity").asLong()),node.path("interval").asText(),node.path("intervalCount").asInt(),false));}else items.add(new Item("item",currency,testCase.path("periodAmount").asLong(),BigDecimal.valueOf(testCase.path("quantity").asLong()),testCase.path("interval").asText(),testCase.path("intervalCount").asInt(),false));List<Discount> discounts=new ArrayList<>();if(testCase.has("discountPercent"))discounts.add(new Discount("discount",null,testCase.path("discountPercent").decimalValue(),null,null,at.minusDays(1),at.plusDays(1)));if(testCase.has("discountAmount"))discounts.add(new Discount("discount",null,null,testCase.path("discountAmount").asLong(),currency,at.minusDays(1),at.plusDays(1)));service.recordAndReplay(new SubscriptionState(w,customer,"sub-"+id,at,testCase.path("state").asText(),"golden-snapshot-"+id,items,discounts));assertThat(service.snapshots(w,customer)).as(id).singleElement().satisfies(result->{assertThat(result.supported()).isTrue();assertThat(result.amountMinor()).isEqualTo(testCase.path("expectedMrr").asLong());});}
 }
 @Test void everyApprovedGoldenMovementTypeExecutesAgainstTheProductionEngine(){
  int index=0;for(JsonNode testCase:golden.path("movementCases")){String id=testCase.path("id").asText(),customer="movement-"+index++,subscription="sub-"+id;OffsetDateTime at=OffsetDateTime.parse(testCase.path("effectiveAt").asText());long before=testCase.path("before").asLong(),after=testCase.path("after").asLong();boolean ever=testCase.path("everPositiveBefore").asBoolean();List<SubscriptionState> states=new ArrayList<>();if(before>0)states.add(state(customer,subscription,at.minusDays(2).toString(),"active","golden-before-"+id,item("item","USD",before,1,"month",1)));else if(ever){states.add(state(customer,subscription,at.minusDays(4).toString(),"active","golden-ever-"+id,item("item","USD",Math.max(after,1),1,"month",1)));states.add(state(customer,subscription,at.minusDays(2).toString(),"canceled","golden-zero-"+id,item("item","USD",Math.max(after,1),1,"month",1)));}if(after>0)states.add(state(customer,subscription,at.toString(),"active","golden-after-"+id,item("item","USD",after,1,"month",1)));else states.add(state(customer,subscription,at.toString(),"canceled","golden-after-"+id,item("item","USD",Math.max(before,1),1,"month",1)));service.recordAndReplay(states);List<Movement> atTarget=service.movements(w,customer).stream().filter(m->m.effectiveAt().toInstant().equals(at.toInstant())).toList();if("NONE".equals(testCase.path("expectedType").asText()))assertThat(atTarget).as(id).isEmpty();else assertThat(atTarget).as(id).singleElement().satisfies(m->{assertThat(m.type()).isEqualTo(testCase.path("expectedType").asText());assertThat(m.amountMinor()).isEqualTo(testCase.path("expectedAmount").asLong());});}
 }
 @Test void itemScopedPercentageDiscountDoesNotDiscountSiblingItems(){
  var at=OffsetDateTime.parse("2026-01-01T00:00:00Z");var discount=new Discount("discount","discounted",BigDecimal.valueOf(50),null,null,at.minusDays(1),at.plusDays(1));service.recordAndReplay(new SubscriptionState(w,"cus","sub",at,"active","scoped-discount",List.of(item("discounted","USD",1000,1,"month",1),item("full-price","USD",1000,1,"month",1)),List.of(discount)));assertThat(service.snapshots(w,"cus")).singleElement().extracting(Snapshot::amountMinor).isEqualTo(1500L);
 }
 @Test void aFailedBatchRollsBackEveryHistoryAndDerivedWrite(){
  var good=state("cus","a","2026-01-01T00:00:00Z","active","ok",item("a","USD",1000,1,"month",1));
  var bad=new SubscriptionState(w,"cus","b",OffsetDateTime.parse("2026-02-01T00:00:00Z"),"active","bad",List.of(new Item("", "USD",1000L,BigDecimal.ONE,"month",1,false)),List.of());
  assertThatThrownBy(()->service.recordAndReplay(List.of(good,bad))).isInstanceOf(IllegalArgumentException.class);
  assertThat(jdbc.sql("SELECT count(*) FROM revenue_subscription_states WHERE workspace_id=:w").param("w",w).query(Long.class).single()).isZero();
 }
}
