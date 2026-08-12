package com.mrrorigin.revenue;

import static com.mrrorigin.revenue.RevenueModels.*;
import static org.assertj.core.api.Assertions.*;
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

@SpringBootTest @Testcontainers
class RevenueCalculationIntegrationTests {
 @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:18-alpine");
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);}
 @Autowired RevenueCalculationService service; @Autowired JdbcClient jdbc; UUID w;
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
 @Test void aFailedBatchRollsBackEveryHistoryAndDerivedWrite(){
  var good=state("cus","a","2026-01-01T00:00:00Z","active","ok",item("a","USD",1000,1,"month",1));
  var bad=new SubscriptionState(w,"cus","b",OffsetDateTime.parse("2026-02-01T00:00:00Z"),"active","bad",List.of(new Item("", "USD",1000L,BigDecimal.ONE,"month",1,false)),List.of());
  assertThatThrownBy(()->service.recordAndReplay(List.of(good,bad))).isInstanceOf(IllegalArgumentException.class);
  assertThat(jdbc.sql("SELECT count(*) FROM revenue_subscription_states WHERE workspace_id=:w").param("w",w).query(Long.class).single()).isZero();
 }
}
