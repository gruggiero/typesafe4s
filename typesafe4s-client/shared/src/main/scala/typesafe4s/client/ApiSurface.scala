package typesafe4s.client

// ============================================================================
// spec: effect-portability — Scenario: A compile-time check proves the
// facade's surface is complete.
//
// Every facade `object TypesafeClient extends ApiSurface[F]`: the object
// cannot satisfy the trait without implementing every operation the SDK
// declares part of the client's surface, so a forgotten operation stays
// abstract and fails THAT ROW's compile naming the missing member.
// ============================================================================
trait ApiSurface[F[_]] {
  def of(config: TypesafeConfig): Client[F]
  def of(config: TypesafeConfig, transport: Transport): Client[F]
}
