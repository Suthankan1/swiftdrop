package com.swiftdrop.deliveryservice;
import com.swiftdrop.deliveryservice.booking.*;
import com.swiftdrop.deliveryservice.quote.*;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
/** Uses PostgreSQL 16 Testcontainers by default. An explicitly supplied disposable DB is also supported. */
class BookingPostgresIT {
 static PostgreSQLContainer postgres;
 static DriverManagerDataSource ds;
 static JdbcTemplate jdbc;
 static ValidatorFactory validators;
 final UUID quote=UUID.randomUUID();
 final UUID key=UUID.randomUUID();
 final BookingActor actor=new BookingActor("customer-"+UUID.randomUUID());
 final JsonMapper json=JsonMapper.builder().build();
 final Instant now=Instant.parse("2026-10-24T12:00:00Z");
 BookingQuoteProvider provider;
 BookingAuthorization authorization;
 BookingRepository repository;
 BookingService service;
 @BeforeAll static void database() {
  String url=System.getProperty("delivery.test.db.url",System.getenv("DELIVERY_TEST_DB_URL"));
  String user=System.getProperty("delivery.test.db.username",System.getenv("DELIVERY_TEST_DB_USERNAME"));
  String password=System.getenv("DELIVERY_TEST_DB_PASSWORD");
  if(url==null) {
   postgres=new PostgreSQLContainer("postgres:16-alpine").withDatabaseName("delivery_test");postgres.start();
   url=postgres.getJdbcUrl();user=postgres.getUsername();password=postgres.getPassword();
  }
  String schema="booking_it_"+UUID.randomUUID().toString().replace("-", "");
  url+=(url.contains("?")?"&":"?")+"currentSchema="+schema;
  ds=new DriverManagerDataSource(url,user,password==null?"":password);
  Flyway.configure().dataSource(ds).schemas(schema).load().migrate();
  jdbc=new JdbcTemplate(ds);validators=Validation.buildDefaultValidatorFactory();
 }
 @AfterAll static void close() { if(validators!=null) validators.close();if(postgres!=null) postgres.stop(); }
 @BeforeEach void setup() {
  repository=new BookingRepository(jdbc,json);provider=mock(BookingQuoteProvider.class);authorization=mock(BookingAuthorization.class);
  when(provider.validate(eq(quote),any())).thenReturn(new QuoteValidation(true,quote,"RATE-V1",50000,"LKR",now.plusSeconds(900),"VALID"));
  service=newService(repository);
 }
 BookingService newService(BookingRepository repo) {
  return new BookingService(repo,provider,authorization,json,Clock.fixed(now,ZoneOffset.UTC),validators.getValidator(),new DataSourceTransactionManager(ds));
 }
 BookParcelRequest request() { return new BookParcelRequest(quote,"Pickup address","Dropoff address","Recipient","+94770000000",null); }
 @Test void persistsSnapshotHistoryOutboxAndStableResponseAtomically() {
  var first=service.book(actor,key,request());
  assertThat(count("parcels")).isEqualTo(1);
  assertThat(jdbc.queryForObject("SELECT quote_snapshot->>'verifiedAmountMinorUnits' FROM parcels WHERE quote_id=?",String.class,quote)).isEqualTo("50000");
  assertThat(jdbc.queryForObject("SELECT count(*) FROM status_history WHERE parcel_id=?",Integer.class,first.parcelId())).isEqualTo(1);
  String event=jdbc.queryForObject("SELECT payload::text FROM outbox_events WHERE parcel_id=?",String.class,first.parcelId());
  assertThat(event).contains("parcel.status.changed.v1","CREATED").doesNotContain("Pickup address","Recipient","+9477",actor.subject());
  // A replay remains stable even if Pricing is now unavailable or the quote expired.
  when(provider.validate(any(),any())).thenThrow(new BookingFailure(503,"QUOTE_UNAVAILABLE","Unavailable."));
  assertThat(service.book(actor,key,request())).isEqualTo(first);
  verify(provider,times(1)).validate(quote,actor.subject());
 }
 @Test void sameKeyDifferentPayloadConflicts() {
  service.book(actor,key,request());
  var changed=new BookParcelRequest(quote,"Different","Dropoff address","Recipient","+94770000000",null);
  var error=catchThrowableOfType(() -> service.book(actor,key,changed),BookingFailure.class);
  assertThat(error.code()).isEqualTo("IDEMPOTENCY_CONFLICT");assertThat(count("parcels")).isEqualTo(1);
 }
 @Test void sameQuoteDifferentKeyConflicts() {
  service.book(actor,key,request());
  var error=catchThrowableOfType(() -> service.book(actor,UUID.randomUUID(),request()),BookingFailure.class);
  assertThat(error.code()).isEqualTo("QUOTE_ALREADY_CONSUMED");assertThat(count("parcels")).isEqualTo(1);
 }
 @Test void concurrentIdenticalRequestsProduceOneBooking() throws Exception {
  var results=concurrent(false);
  assertThat(results).allMatch(r -> r instanceof ParcelBooking);
  assertThat(results.stream().distinct().count()).isEqualTo(1);assertThat(count("parcels")).isEqualTo(1);
 }
 @Test void concurrentDifferentKeysCannotConsumeSameQuoteTwice() throws Exception {
  var results=concurrent(true);
  assertThat(results.stream().filter(r -> r instanceof ParcelBooking).count()).isEqualTo(1);
  assertThat(results.stream().filter(r -> r instanceof BookingFailure f && f.code().equals("QUOTE_ALREADY_CONSUMED")).count()).isEqualTo(7);
  assertThat(count("parcels")).isEqualTo(1);
 }
 List<Object> concurrent(boolean differentKeys) throws Exception {
  var ready=new CountDownLatch(8);var go=new CountDownLatch(1);
  try(var pool=Executors.newFixedThreadPool(8)) {
   var futures=IntStream.range(0,8).mapToObj(i -> pool.submit(() -> {
    ready.countDown();if(!go.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Barrier timeout");
    try { return (Object) service.book(actor,differentKeys?UUID.randomUUID():key,request()); }
    catch(BookingFailure e) { return (Object)e; }
   })).toList();
   assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();go.countDown();
   var results=new ArrayList<Object>();for(var f:futures) results.add(f.get(20,TimeUnit.SECONDS));return results;
  }
 }
 @Test void failureAfterWritesRollsBackAllFourRecords() {
  int historyBefore=jdbc.queryForObject("SELECT count(*) FROM status_history",Integer.class);
  int outboxBefore=jdbc.queryForObject("SELECT count(*) FROM outbox_events",Integer.class);
  var failing=spy(repository);
  doAnswer(invocation -> { invocation.callRealMethod();throw new IllegalStateException("Injected transaction failure"); })
   .when(failing).finish(any(),any(),any(),any(),any());
  assertThatThrownBy(() -> newService(failing).book(actor,key,request())).isInstanceOf(IllegalStateException.class);
  assertThat(count("parcels")).isZero();
  assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE actor_sub=?",Integer.class,actor.subject())).isZero();
  assertThat(jdbc.queryForObject("SELECT count(*) FROM status_history",Integer.class)).isEqualTo(historyBefore);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events",Integer.class)).isEqualTo(outboxBefore);
  // Immediate retry succeeds: failed attempt did not consume quote or key.
  service.book(actor,key,request());assertThat(count("parcels")).isEqualTo(1);
 }
 @Test void expiryAndUnauthorizedQuoteLeaveNoRows() {
  when(provider.validate(any(),any())).thenReturn(new QuoteValidation(true,quote,"v",50000,"LKR",now,"VALID"));
  assertThatThrownBy(() -> service.book(actor,key,request())).isInstanceOf(BookingFailure.class);
  doThrow(new BookingFailure(403,"QUOTE_FORBIDDEN","Denied.")).when(authorization).requireAllowed(any(),any());
  assertThatThrownBy(() -> service.book(actor,key,request())).isInstanceOf(BookingFailure.class);
  assertThat(count("parcels")).isZero();
 }
 @Test void immutableProvenanceAndDatabaseUniqueness() {
  var booked=service.book(actor,key,request());
  assertThatThrownBy(() -> jdbc.update("UPDATE parcels SET amount_minor_units=1 WHERE id=?",booked.parcelId()))
   .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  assertThatThrownBy(() -> jdbc.update("UPDATE parcels SET created_by_sub='intruder' WHERE id=?",booked.parcelId()))
   .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  assertThatThrownBy(() -> jdbc.update("DELETE FROM status_history WHERE parcel_id=?",booked.parcelId()))
   .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
 }
 private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE quote_id=?",Integer.class,quote); }
}
