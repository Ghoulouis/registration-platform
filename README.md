# REGISTRATION SYSTEM

## Build & Config & Run

## Mô tả ngắn về kiến trúc hệ thống
- Mô hình tổng thể Client/Server
- Giao thức mạng được chọn TCP
### Sơ đồ tổng thể

### Mô hình xử lí

### Cấu trúc và quản lí bộ nhớ


### Thiết kế xác thực & an toàn thông tin
Khoá bất đối xứng Ed25519 + nonce thay đổi qua mỗi lần đăng kí/gia hạn

### Mã nguồn & kịch bản kiểm thử


| Kịch bản yêu cầu | Kịch bản kiểm thử | Source | Kết quả |
|---|---|---|---|
| Đăng kí lần đầu thành công | Client thực hiện REGISTER 2 bước (xin Nonce, rồi gửi chữ ký hợp lệ trên Nonce đó); Server xác nhận, trả `SUCCESS` kèm Nonce mới và `validityPeriodSeconds` đúng cấu hình. | [`RegistrationServiceTest.registerNewClientSucceeds`](server/src/test/java/com/registration/server/domain/RegistrationServiceTest.java#L52) | Đạt |
| Đăng kí với thông tin xác thực không đúng | Client ký sai (chữ ký toàn số 0) trên Nonce hợp lệ; Server từ chối bằng `CHALLENGE_REJECTED` thay vì xác nhận đăng ký. | [`RegistrationServiceTest.wrongSignatureIsRejected`](server/src/test/java/com/registration/server/domain/RegistrationServiceTest.java#L73) | Đạt |
| Gia hạn đăng kí thành công | Client đã đăng ký gửi RENEW với chữ ký hợp lệ trên Nonce hiện tại; Server trả `SUCCESS` kèm Nonce mới (khác Nonce cũ) và `validityPeriodSeconds` đầy đủ. | [`RegistrationServiceTest.renewRegisteredClientSucceeds`](server/src/test/java/com/registration/server/domain/RegistrationServiceTest.java#L107) | Đạt |
| Bản ghi tự động hết hạn khi không được gia hạn | Hai cơ chế song song (ADR-0016): HashedWheelTimer tự xoá bản ghi ngay khi tới hạn dù reaper chưa chạy; reaper (quét định kỳ, defense-in-depth) cũng xoá đúng bản ghi đã hết hạn và không đụng tới bản ghi còn sống. | [`InMemoryRegistrationStoreTest.timerEvictsExpiredRegistrationWithoutTheReaper`](server/src/test/java/com/registration/server/store/InMemoryRegistrationStoreTest.java#L172), [`.reaperEvictsExpiredRegistration`](server/src/test/java/com/registration/server/store/InMemoryRegistrationStoreTest.java#L138) | Đạt |
| Client chủ động hủy đăng ký thành công | Client đã đăng ký gửi CANCEL với chữ ký hợp lệ; Server xoá bản ghi và trả `SUCCESS`. | [`RegistrationServiceTest.cancelRegisteredClientSucceeds`](server/src/test/java/com/registration/server/domain/RegistrationServiceTest.java#L159) | Đạt |
| Challenge hết hạn | Nonce PENDING (challenge) chưa được xác nhận trong `pendingNonceTtl` tự bị xoá bởi Timer, kể cả khi reaper chưa kịp quét. | [`InMemoryRegistrationStoreTest.timerEvictsExpiredPendingNonceWithoutTheReaper`](server/src/test/java/com/registration/server/store/InMemoryRegistrationStoreTest.java#L159), [`.reaperEvictsExpiredPendingNonce`](server/src/test/java/com/registration/server/store/InMemoryRegistrationStoreTest.java#L128) | Đạt |
| Challenge bị sử dụng lại | Sau 1 lần xác thực sai khiến Nonce PENDING bị huỷ (single-use, ADR-0009), gửi lại đúng chữ ký hợp lệ trên Nonce cũ đó vẫn bị từ chối (`CHALLENGE_REJECTED`) vì Nonce đã bị tiêu huỷ, không phải xác nhận nhầm. | [`RegistrationServiceTest.pendingNonceCannotBeReusedAfterAFailedAttempt`](server/src/test/java/com/registration/server/domain/RegistrationServiceTest.java#L83) | Đạt |
| Thiếu trường dữ liệu hoặc sai định dạng | Gửi 1 frame với `MessageType` không hợp lệ: Server đóng đúng kết nối đó (không hang/crash) và vẫn phục vụ bình thường các kết nối khác ngay sau đó. Ở tầng thấp hơn, `FrameDecoder` cũng từ chối payload length vượt giới hạn cho phép trước khi chạm tới logic nghiệp vụ. | [`TcpServerTest.malformedFrameClosesOnlyThatConnectionAndServerKeepsRunning`](server/src/test/java/com/registration/server/net/TcpServerTest.java#L115), [`FrameDecoderTest.rejectsFrameWithOversizedPayloadLength`](common/src/test/java/com/registration/common/protocol/FrameDecoderTest.java#L43) | Đạt |
| Clients chạy bình thường nếu mất kết nối với Server | Lần gọi đầu tiên timeout (mô phỏng mất kết nối/server không phản hồi); `RetryingRequester` tự động retry và thành công ở lần thử tiếp theo mà không ném lỗi lên tầng trên. | [`RetryingRequesterTest.succeedsAfterTimeoutThenRetrySucceeds`](client/src/test/java/com/registration/client/retry/RetryingRequesterTest.java#L66) | Đạt |
| Nhiều Client đăng kí đồng thời | 50 Client Id khác nhau gửi REGISTER đồng thời (mỗi Client 1 virtual thread); tất cả đều nhận `SUCCESS`, không deadlock/race condition trên store dùng chung. | [`TcpServerTest.manyDifferentClientsCanRegisterConcurrently`](server/src/test/java/com/registration/server/net/TcpServerTest.java#L135) | Đạt |
| Yêu cầu bị gửi trùng hoặc đến không đúng thứ tự | (a) RENEW gửi lại với Nonce cũ vì response lần đầu bị thất lạc vẫn được chấp nhận `SUCCESS` mà không rotate thêm lần nữa (ADR-0010's grace window); (b) REGISTER gửi trùng (Client đã đăng ký lại gửi initial REGISTER) bị từ chối rõ ràng bằng `ALREADY_REGISTERED`; (c) phía Client, retry sau khi nhận `ALREADY_REGISTERED` vẫn được tính là thành công thay vì lỗi (ADR-0005). | [`RegistrationServiceTest.renewWithThePreviousNonceIsToleratedAsARetryAndDoesNotRotateAgain`](server/src/test/java/com/registration/server/domain/RegistrationServiceTest.java#L137), [`TcpServerTest.duplicateRegisterIsRejected`](server/src/test/java/com/registration/server/net/TcpServerTest.java#L82), [`RetryingRequesterTest.alreadyRegisteredOnRetryIsTreatedAsSuccess`](client/src/test/java/com/registration/client/retry/RetryingRequesterTest.java#L83) | Đạt |





