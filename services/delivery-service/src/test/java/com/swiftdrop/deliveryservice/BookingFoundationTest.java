package com.swiftdrop.deliveryservice;
import com.swiftdrop.deliveryservice.booking.*;
import com.swiftdrop.deliveryservice.quote.*;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class BookingFoundationTest {
 static ValidatorFactory validators;
 final BookingRepository repository=mock(BookingRepository.class);
 final BookingQuoteProvider provider=mock(BookingQuoteProvider.class);
 final BookingAuthorization authorization=mock(BookingAuthorization.class);
 final UUID quote=UUID.randomUUID();
 final UUID key=UUID.randomUUID();
 final Instant now=Instant.parse("2026-10-24T12:00:00Z");
 final BookingActor actor=new BookingActor("customer-a");
 BookingService service;
 @BeforeAll static void setupValidator() { validators=Validation.buildDefaultValidatorFactory(); }
 @AfterAll static void closeValidator() { validators.close(); }
 @BeforeEach void setup() {
  var manager=mock(org.springframework.transaction.PlatformTransactionManager.class);
  when(manager.getTransaction(any())).thenReturn(mock(org.springframework.transaction.TransactionStatus.class));
  service=new BookingService(repository,provider,authorization,JsonMapper.builder().build(),Clock.fixed(now,ZoneOffset.UTC),validators.getValidator(),manager);
  when(repository.replay(anyString(),any())).thenReturn(Optional.empty());
  when(repository.insert(any(),any(),anyString())).thenReturn(true);
  when(provider.validate(quote,actor.subject())).thenReturn(valid(now.plusSeconds(60)));
 }
 BookParcelRequest request() { return new BookParcelRequest(quote,"Pickup","Dropoff","Recipient","+94770000000",null); }
 QuoteValidation valid(Instant expires) { return new QuoteValidation(true,quote,"RATE-2026-V1",50000,"LKR",expires,"VALID"); }
 @Test void createsOnlyFromServerVerifiedAmountAndActor() {
  var result=service.book(actor,key,request());
  assertThat(result.createdBySub()).isEqualTo(actor.subject());
  assertThat(result.amountMinorUnits()).isEqualTo(50000);
  assertThat(result.currentStatus()).isEqualTo("CREATED");
  assertThat(result.version()).isEqualTo(1);
  assertThat(result.trackingNumber()).matches("SWD-20261024-[A-Z0-9]{8}");
  verify(authorization).requireAllowed(actor.subject(),request());
  verify(repository).finish(eq(result),eq(key),anyString(),any(),anyString());
 }
 @Test void expiryAtExactBoundaryIsRejected() {
  when(provider.validate(any(),any())).thenReturn(valid(now));
  assertFailure(422,"QUOTE_EXPIRED");verify(repository,never()).insert(any(),any(),any());
 }
 @Test void ineligibleAndUnknownStatusFailClosed() {
  when(provider.validate(any(),any())).thenReturn(new QuoteValidation(false,quote,"v",0,"LKR",now.plusSeconds(60),"UNKNOWN"));
  assertFailure(422,"QUOTE_INELIGIBLE");
 }
 @Test void nonexistentQuoteRejectionIsPropagated() {
  when(provider.validate(any(),any())).thenThrow(new BookingFailure(404,"QUOTE_NOT_FOUND","Quote unavailable."));
  assertFailure(404,"QUOTE_NOT_FOUND");
 }
 @Test void authorizationFailureNeverCallsPricingOrWrites() {
  doThrow(new BookingFailure(403,"QUOTE_FORBIDDEN","Quote access denied.")).when(authorization).requireAllowed(any(),any());
  assertFailure(403,"QUOTE_FORBIDDEN");verifyNoInteractions(provider);verify(repository,never()).insert(any(),any(),any());
 }
 @Test void mismatchedProviderQuoteCannotCreateOrphan() {
  when(provider.validate(any(),any())).thenReturn(new QuoteValidation(true,UUID.randomUUID(),"v",100,"LKR",now.plusSeconds(60),"VALID"));
  assertFailure(503,"QUOTE_PROVIDER_INVALID");
 }
 @Test void negativeServerAmountIsRejected() {
  when(provider.validate(any(),any())).thenReturn(new QuoteValidation(true,quote,"v",-1,"LKR",now.plusSeconds(60),"VALID"));
  assertFailure(503,"QUOTE_PROVIDER_INVALID");
 }
 @Test void missingKeyRejectedBeforeAnyIntegration() {
  assertThatThrownBy(() -> service.book(actor,null,request())).isInstanceOf(BookingFailure.class);
  verifyNoInteractions(provider,authorization,repository);
 }
 @Test void structuredValidationDoesNotEchoSensitiveValues() {
  var invalid=new BookParcelRequest(null,"", "Dropoff", "Recipient", "phone-secret-".repeat(10),null);
  var failure=catchThrowableOfType(() -> service.book(actor,key,invalid),BookingFailure.class);
  assertThat(failure.status()).isEqualTo(400);
  assertThat(failure.problem().getProperties()).containsKey("invalidParameters");
  assertThat(failure.problem().toString()).doesNotContain("phone-secret");
  verifyNoInteractions(provider,authorization,repository);
 }
 @Test void roleAndSubjectAreDerivedFromAuthenticatedPrincipal() {
  var jwt=Jwt.withTokenValue("test").header("alg","RS256").subject("customer-a").build();
  var customer=new JwtAuthenticationToken(jwt,List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
  assertThat(BookingActor.from(customer).subject()).isEqualTo("customer-a");
  var merchant=new JwtAuthenticationToken(jwt,List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")));
  assertThatThrownBy(() -> BookingActor.from(merchant)).isInstanceOf(BookingFailure.class);
  assertThatThrownBy(() -> BookingActor.from(null)).isInstanceOf(BookingFailure.class);
 }
 @Test void requestDoesNotAcceptFinancialOrTenantFields() {
  var json=JsonMapper.builder().enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
  String body=json.writeValueAsString(request());
  for(String field:List.of("amountMinorUnits","currency","merchantId","createdBySub")) {
   String forged=body.substring(0,body.length()-1)+",\""+field+"\":\"forged\"}";
   assertThatThrownBy(() -> json.readValue(forged,BookParcelRequest.class)).isInstanceOf(RuntimeException.class);
  }
 }
 private void assertFailure(int status,String code) {
  var failure=catchThrowableOfType(() -> service.book(actor,key,request()),BookingFailure.class);
  assertThat(failure.status()).isEqualTo(status);assertThat(failure.code()).isEqualTo(code);
 }
}
