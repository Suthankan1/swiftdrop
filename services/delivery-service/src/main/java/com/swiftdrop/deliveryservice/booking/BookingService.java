package com.swiftdrop.deliveryservice.booking;
import com.swiftdrop.deliveryservice.quote.*;
import jakarta.validation.Validator;
import org.springframework.transaction.support.TransactionTemplate;


import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
public class BookingService {
 private final BookingRepository repository;
 private final BookingQuoteProvider quotes;
 private final JsonMapper json;
 private final Clock clock;
 private final BookingAuthorization authorization;
 private final Validator validator;
 private final TransactionTemplate transaction;
 public BookingService(BookingRepository repository, BookingQuoteProvider quotes, BookingAuthorization authorization,
   JsonMapper json, Clock clock, Validator validator, org.springframework.transaction.PlatformTransactionManager manager) {
  this.repository=repository;this.quotes=quotes;this.authorization=authorization;this.json=json;this.clock=clock;this.validator=validator;
  this.transaction=new TransactionTemplate(manager);
  this.transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
 }
 /** No HTTP controller or production bean is registered until the external contract is approved. */
 public ParcelBooking book(BookingActor actor, UUID key, BookParcelRequest request) {
  if(actor==null) throw failure(401,"UNAUTHENTICATED","Authentication required.");
  if(key==null) throw failure(400,"INVALID_REQUEST","Idempotency-Key is required for the foundation command.");
  if(request==null) throw failure(400,"INVALID_REQUEST","Booking request is required.");
  var violations=validator.validate(request);
  if(!violations.isEmpty()) throw BookingFailure.validation(violations.stream()
   .map(v -> new BookingFailure.InvalidParameter(v.getPropertyPath().toString(),v.getMessage()))
   .sorted(java.util.Comparator.comparing(BookingFailure.InvalidParameter::field)).toList());
  return transaction.execute(status -> create(actor.subject(),key,request));
 }
 private ParcelBooking create(String subject, UUID key, BookParcelRequest request) {
  String digest=digest(json.writeValueAsString(request));
  repository.lock(subject,key);
  var replay=repository.replay(subject,key);
  if(replay.isPresent()) {
   if(!replay.get().digest().equals(digest)) throw failure(409,"IDEMPOTENCY_CONFLICT","Idempotency key was used for a different request.");
   return replay.get().response();
  }
  // Provider must reject unknown/non-owned/ineligible quotes without disclosing their details.
  authorization.requireAllowed(subject,request);
  QuoteValidation quote=quotes.validate(request.quoteId(),subject);
  Instant now=clock.instant();
  validate(quote,request,now);
  if(repository.quoteUsed(request.quoteId())) throw failure(409,"QUOTE_ALREADY_CONSUMED","Quote already booked.");
  for(int attempt=0;attempt<5;attempt++) {
   UUID id=UUID.randomUUID();
   String tracking="SWD-"+DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC).format(now)+"-"+UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.ROOT);
   var parcel=new ParcelBooking(id,tracking,subject,request.quoteId(),"CREATED",1,
    quote.verifiedAmountMinorUnits(),quote.currency(),quote.rateVersion(),request.pickupAddress(),request.dropoffAddress(),now);
   if(repository.insert(parcel,request,json.writeValueAsString(quote))) {
    UUID eventId=UUID.randomUUID();
    var payload=new LinkedHashMap<String,Object>();
    payload.put("parcelId",id);payload.put("trackingNumber",tracking);payload.put("oldStatus",null);payload.put("newStatus","CREATED");
    var event=Map.of("eventId",eventId,"eventType","parcel.status.changed.v1","schemaVersion","1.0.0",
     "aggregateId",id,"aggregateType","PARCEL","aggregateVersion",1,"occurredAt",now,
     "correlationId",key.toString(),"producer","delivery-operations","payload",payload);
    repository.finish(parcel,key,digest,eventId,json.writeValueAsString(event));
    return parcel;
   }
   if(repository.quoteUsed(request.quoteId())) throw failure(409,"QUOTE_ALREADY_CONSUMED","Quote already booked.");
  }
  throw failure(503,"BOOKING_UNAVAILABLE","Booking could not be completed. Retry with the same key.");
 }
 private void validate(QuoteValidation q, BookParcelRequest r, Instant now) {
  if(q==null) throw failure(503,"QUOTE_PROVIDER_INVALID","Quote validation is unavailable.");
  if(!r.quoteId().equals(q.quoteId())) throw failure(503,"QUOTE_PROVIDER_INVALID","Quote validation is unavailable.");
  if(!q.isValid()) {
   // Only codes named in the existing Pricing contract are mapped; do not expose remote details.
   if("QUOTE_EXPIRED".equals(q.validationCode())) throw failure(422,"QUOTE_EXPIRED","Quote expired. Request a new quote.");
   if("RATE_SUPERSEDED".equals(q.validationCode())) throw failure(422,"QUOTE_RATE_SUPERSEDED","Quote rate is no longer eligible.");
   throw failure(422,"QUOTE_INELIGIBLE","Quote is not eligible for booking.");
  }
  if(!"VALID".equals(q.validationCode()) || q.expiresAt()==null || q.currency()==null
    || !q.currency().matches("[A-Z]{3}") || q.rateVersion()==null || q.rateVersion().isBlank()
    || q.verifiedAmountMinorUnits()<0)
   throw failure(503,"QUOTE_PROVIDER_INVALID","Quote validation is unavailable.");
  if(!q.expiresAt().isAfter(now)) throw failure(422,"QUOTE_EXPIRED","Quote expired. Request a new quote.");
 }
 private static String digest(String input) {
  try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
  catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
 }
 private static BookingFailure failure(int status,String code,String detail) { return new BookingFailure(status,code,detail); }
}
