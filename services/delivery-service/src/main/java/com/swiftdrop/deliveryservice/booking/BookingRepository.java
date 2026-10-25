package com.swiftdrop.deliveryservice.booking;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import java.util.Optional;
import java.util.UUID;
@Repository
public class BookingRepository {
 private final JdbcTemplate jdbc;
 private final JsonMapper json;
 public BookingRepository(JdbcTemplate jdbc, JsonMapper json) { this.jdbc=jdbc; this.json=json; }
 public record Replay(String digest, ParcelBooking response) {}
 public void lock(String subject, UUID key) {
  // Hash collisions only serialize unrelated commands; persisted identity remains exact.
  jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {}, subject+":"+key);
 }
 public Optional<Replay> replay(String subject, UUID key) {
  return jdbc.query("SELECT request_digest, response_json::text FROM idempotency_records WHERE actor_sub=? AND idempotency_key=?",
   (rs,n) -> new Replay(rs.getString(1),json.readValue(rs.getString(2),ParcelBooking.class)),subject,key).stream().findFirst();
 }
 public boolean quoteUsed(UUID quote) {
  return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM parcels WHERE quote_id=?)",Boolean.class,quote));
 }
 public boolean insert(ParcelBooking p, BookParcelRequest r, String snapshot) {
  return jdbc.update("""
   INSERT INTO parcels (id,tracking_number,created_by_sub,quote_id,current_status,version,
    pickup_address,dropoff_address,recipient_name,recipient_phone,delivery_notes,
    amount_minor_units,currency,rate_version,quote_snapshot,created_at)
   VALUES (?,?,?,?, 'CREATED',1,?,?,?,?,?,?,?,?,?::jsonb,?) ON CONFLICT DO NOTHING
   """, p.parcelId(),p.trackingNumber(),p.createdBySub(),r.quoteId(),r.pickupAddress(),r.dropoffAddress(),
   r.recipientName(),r.recipientPhone(),r.deliveryNotes(),p.amountMinorUnits(),p.currency(),p.rateVersion(),snapshot,
   java.sql.Timestamp.from(p.createdAt()))==1;
 }
 public void finish(ParcelBooking p, UUID key, String digest, UUID eventId, String event) {
  jdbc.update("INSERT INTO status_history (id,parcel_id,state_version,old_status,new_status,occurred_at) VALUES (?,?,1,NULL,'CREATED',?)",
   UUID.randomUUID(),p.parcelId(),java.sql.Timestamp.from(p.createdAt()));
  jdbc.update("INSERT INTO outbox_events (event_id,parcel_id,aggregate_version,event_type,payload,created_at) VALUES (?,?,1,'parcel.status.changed.v1',?::jsonb,?)",
   eventId,p.parcelId(),event,java.sql.Timestamp.from(p.createdAt()));
  jdbc.update("INSERT INTO idempotency_records (actor_sub,idempotency_key,request_digest,parcel_id,response_json,created_at) VALUES (?,?,?,?,?::jsonb,?)",
   p.createdBySub(),key,digest,p.parcelId(),json.writeValueAsString(p),java.sql.Timestamp.from(p.createdAt()));
 }
}
