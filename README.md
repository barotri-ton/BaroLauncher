<div align="center">

# Material TV Launcher

**Một launcher mượt mà cho Android TV và Google TV.**

Những app bạn dùng nhiều nhất, xếp theo thứ tự bạn dùng, với màu sắc thay đổi theo app đang được chọn.
Không quảng cáo, không gợi ý nội dung, không theo dõi, chỉ tốn khoảng 40 MB bộ nhớ. Launcher chỉ kết nối mạng để lấy thời tiết, ảnh màn hình chờ và poster hoặc logo kênh mà app cung cấp; mỗi mục đều chỉnh được trong Launcher settings.

[![Tải APK](https://img.shields.io/badge/T%E1%BA%A3i-APK-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://github.com/barotri-ton/BaroLauncher/releases/latest/download/BaroLauncher.apk)

</div>

![Material TV Launcher: Spotify đang được chọn, cả màn hình chuyển sang màu xanh lá của Spotify, biểu tượng equalizer dưới ô app, thẻ Now Playing với thanh tiến trình gợn sóng, và dock gồm sáu app dùng nhiều nhất](docs/home-playing.png)

## Có gì trong launcher

- **App xếp theo mức sử dụng.** Dock hiện sáu app bạn dùng nhiều nhất, app dùng nhiều nhất nằm bên trái. Thứ tự
  tự thay đổi theo thói quen của bạn. Không có danh sách app nào được viết cứng trong code, nên launcher chạy được trên TV của bất kỳ ai.
- **Màu theo app đang chọn.** Khi bạn di chuyển, nền, hình khối, các nút và thẻ Now Playing đổi sang màu của app
  đang được chọn, dùng thư viện
  [material-color-utilities](https://github.com/material-foundation/material-color-utilities) của chính Google (cùng
  thuật toán tạo màu từ hình nền của Android).
- **Máy chơi game hiện đúng tên.** Thay vì một ô "Live TV" chung chung, các thiết bị tự giới thiệu qua HDMI
  (PlayStation 5, Xbox) có ô riêng, kèm chấm xanh khi thiết bị đang bật. Chọn ô đó là TV chuyển thẳng sang
  cổng HDMI tương ứng.
- **Now Playing.** Khi nhạc hoặc video đang phát trong bất kỳ app nào (Spotify, YouTube Music, ...), một thẻ hiện ảnh bìa,
  tên bài, thanh tiến trình gợn sóng và nút phát/tạm dừng, và một biểu tượng equalizer nhỏ đánh dấu app đang phát trong dock.
- **Material 3 Expressive.** Chuyển động kiểu lò xo, viền phát sáng màu khi chọn, hình khối từ thư viện hình của
  Material biến đổi khi bạn di chuyển, thanh nút phía trên, các ô bên cạnh dạt ra nhường chỗ, biểu tượng tải biến hình
  khi app mở chậm, và đồng hồ font Google Sans Flex có độ đậm thay đổi theo thời điểm trong ngày.
- **Chuyển động mượt.** Hiệu ứng chạy khớp với tần số quét của TV. Đã thử trên chip TV bốn nhân,
  tốn khoảng 40 MB bộ nhớ. Không có hiệu ứng hay truy vấn nào chạy khi bạn đang ở app khác.

**Kèm một màn hình chờ đổi màu theo mặt trời.** *Material TV Clock* vẽ một hình nền mới theo phong cách Material You
mỗi giờ: xanh chàm về đêm, màu đào lúc bình minh, xanh trời buổi trưa, cam lúc hoàng hôn.

![Màn hình chờ: đồng hồ lớn, ngày tháng và bài hát đang phát trên nền các hình khối Material mềm màu xanh lá lúc chiều tối](docs/screensaver.png)

## Tải và cài lên TV

Google TV bỏ qua mọi màn hình chính không phải của nó, nên ngoài việc cài APK, launcher cần được bật thêm vài quyền
qua ADB (công cụ gỡ lỗi của Android). Không có gì trên TV bị xóa, và một lệnh duy nhất trả mọi thứ về như cũ.

**Bạn cần:** một máy tính cùng mạng Wi-Fi với TV, đã cài
[ADB (Android platform-tools)](https://developer.android.com/tools/releases/platform-tools)
(gõ `adb version` trong terminal thấy ra phiên bản là được).

### Bước 1: Bật gỡ lỗi trên TV

1. Vào **Cài đặt → Hệ thống → Giới thiệu** (Android TV: **Cài đặt → Tùy chọn thiết bị → Giới thiệu**), chọn
   **Bản dựng hệ điều hành Android TV** 7 lần liên tiếp cho tới khi thấy thông báo bạn đã là nhà phát triển.
2. Quay lại, mở **Tùy chọn nhà phát triển** và bật **Gỡ lỗi USB** (và **Gỡ lỗi qua mạng** nếu có).
3. Ghi lại địa chỉ IP của TV trong **Cài đặt → Mạng và Internet** (ví dụ `192.168.1.50`).

### Bước 2: Tải file

Tải ba file của bản mới nhất từ trang [Releases](https://github.com/barotri-ton/BaroLauncher/releases/latest)
vào cùng một thư mục trên máy tính:

- [`BaroLauncher.apk`](https://github.com/barotri-ton/BaroLauncher/releases/latest/download/BaroLauncher.apk): app launcher
- [`install.sh`](https://github.com/barotri-ton/BaroLauncher/releases/latest/download/install.sh): script cài đặt và cấp quyền
- [`uninstall.sh`](https://github.com/barotri-ton/BaroLauncher/releases/latest/download/uninstall.sh): script gỡ, trả lại màn hình chính cũ

Hoặc tải bằng lệnh:

```bash
curl -fLO https://github.com/barotri-ton/BaroLauncher/releases/latest/download/BaroLauncher.apk
curl -fLO https://github.com/barotri-ton/BaroLauncher/releases/latest/download/install.sh
curl -fLO https://github.com/barotri-ton/BaroLauncher/releases/latest/download/uninstall.sh
```

### Bước 3: Cài lên TV

**Cách A: dùng script (macOS, Linux, hoặc Windows có Git Bash).** Trong thư mục chứa ba file, chạy với IP của TV:

```bash
bash install.sh 192.168.1.50
```

Lần đầu kết nối, TV sẽ hỏi **Cho phép gỡ lỗi USB?**: chọn **Cho phép** (script sẽ chờ). Khi thấy
`Done: Material TV Launcher is on screen.` là xong.

**Cách B: gõ lệnh adb thủ công (Windows PowerShell không có bash).** Thay `192.168.1.50` bằng IP của TV:

```powershell
adb connect 192.168.1.50:5555
adb install -r BaroLauncher.apk
adb shell cmd notification allow_listener com.rampey.materialtv/com.rampey.materialtv.MediaListener
adb shell pm grant com.rampey.materialtv android.permission.DUMP
adb shell cmd appops set com.rampey.materialtv GET_USAGE_STATS allow
adb shell pm grant com.rampey.materialtv android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant com.rampey.materialtv android.permission.READ_TV_LISTINGS
adb shell settings put secure enabled_accessibility_services com.rampey.materialtv/com.rampey.materialtv.HomeKeyService
adb shell settings put secure accessibility_enabled 1
adb shell am start -n com.rampey.materialtv/.HomeActivity
```

Lưu ý: lệnh `enabled_accessibility_services` ở trên **ghi đè** danh sách dịch vụ trợ năng đang bật. Nếu TV đã có dịch vụ
trợ năng khác, chạy `adb shell settings get secure enabled_accessibility_services` trước, rồi nối thêm
`:com.rampey.materialtv/com.rampey.materialtv.HomeKeyService` vào cuối giá trị đó. Script ở cách A tự làm việc này.

### Bước 4: Bấm Home

Từ giờ nút **Home** trên remote sẽ mở Material TV Launcher. Nếu TV hỏi chọn ứng dụng màn hình chính, chọn
**Material TV Launcher** → **Luôn chọn**.

### Cập nhật và gỡ cài đặt

- **Cập nhật:** tải APK mới và chạy lại bước 3. Nếu báo lỗi chữ ký không khớp (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`),
  bản cũ được ký bằng key khác: gỡ bản cũ trước (cài đặt launcher sẽ mất) rồi cài lại.
- **Gỡ:** `bash uninstall.sh 192.168.1.50` trả lại màn hình chính gốc của TV.

Các quyền được bật và lý do: xem [Quyền riêng tư](#quyền-riêng-tư). Chi tiết thêm (tiếng Anh): [Quick start](AGENTS.md#quick-start),
[cài bằng coding agent](AGENTS.md#install-with-a-coding-agent), [xử lý sự cố](AGENTS.md#troubleshooting).

## TV được hỗ trợ

Mọi TV chạy Android TV hoặc Google TV từ **Android 9 trở lên**, của bất kỳ hãng nào. Đã thử trên Google TV và máy chiếu
Android 9 RAM thấp (Movie Legend A7 Ultra). Chưa thử:
Android TV cổ điển (nút Home sẽ hoạt động giống vậy) và Amazon Fire TV (Fire OS, dựa trên
Android). Không hỗ trợ: Samsung (Tizen) và LG (webOS).

Bạn đã thử trên TV của mình? Một báo cáo ngắn kèm ảnh chụp màn hình sẽ giúp ích cho người khác, xem [Đóng góp](#đóng-góp).

## Quyền riêng tư

Launcher chỉ kết nối mạng cho các tính năng dưới đây; không tính năng nào gửi đi những gì app đọc được trên TV.

- **Thời tiết** (bật sẵn; Launcher settings -> Weather): tên thành phố bạn gõ, rồi tọa độ của nó, được gửi tới Open-Meteo. Nếu không gõ thành phố, launcher dùng vị trí gần đúng của TV nếu TV có, nếu không thì dùng ipwho.is.
- **Ảnh màn hình chờ** (tắt sẵn): một yêu cầu chỉ chứa kích thước ảnh gửi tới picsum.photos. Hình nền Home có sẵn trong app và dùng offline.
- **Continue watching** (tắt sẵn) và **Kênh TV** (bật sẵn): poster hoặc logo kênh được tải từ địa chỉ https mà chính app đó cung cấp, nên máy chủ ảnh của app đó thấy địa chỉ của TV, giống như khi bạn mở app. Danh sách và lịch sử Continue chỉ lưu trong cài đặt trên TV.

Xem [quyền và cách kiểm tra](AGENTS.md#privacy).

## Đóng góp

Điều hữu ích nhất bạn có thể gửi là báo cáo đã thử trên TV kèm ảnh chụp màn hình. Ý tưởng cho launcher mà vẫn giữ nó gọn nhẹ
cũng được hoan nghênh: xem [CONTRIBUTING.md](CONTRIBUTING.md).

[Hướng dẫn build](AGENTS.md#build-it-yourself) và [xử lý sự cố](AGENTS.md#troubleshooting) nằm trong AGENTS.md.

## Giấy phép

Apache 2.0, xem [`LICENSE`](LICENSE). Đi kèm:
[material-color-utilities](https://github.com/material-foundation/material-color-utilities) (Apache 2.0) và
[Google Sans Flex](https://github.com/googlefonts/googlesans-flex) (SIL Open Font License 1.1). "Google",
"Google Sans" và "Material Design" thuộc về Google; dự án này không liên kết với Google và không được Google bảo trợ.

14 ảnh trong [`assets/wallpapers`](assets/wallpapers) **không** theo giấy phép Apache 2.0: tám ảnh Trái Đất của NASA chụp từ
Trạm Vũ trụ Quốc tế (phạm vi công cộng) và sáu ảnh từ Wikimedia Commons theo giấy phép CC BY-SA 3.0 hoặc 4.0.
Tất cả đều được cắt và thu về 1920 × 1080 (bộ ảnh lấy từ [glassOS-tv](https://github.com/jmarknavarro/glassOS-tv)).
Tên tác giả, tiêu đề và đường dẫn giấy phép nằm trong [`assets/wallpapers/CREDITS.md`](assets/wallpapers/CREDITS.md); hãy giữ file này đi cùng các ảnh.
