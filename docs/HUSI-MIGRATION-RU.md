# KVN 0.2.0 — переход на сетевое ядро Husi

## Результат

Реализован самостоятельный KVN с прежним минимальным Compose-интерфейсом и
сетевыми компонентами Husi v2.3.4. Собственное ядро, Android-приложение и реальные
VPN-тесты Android16 проходят CI. 8 ноября 2026 пользователь сообщил:
«Я проверил на своём телефоне и всё работает», затем разрешил слияние и релиз.
Это физическое подтверждение работы приложения; точный хеш установленного APK,
длительность и отдельные измерения каждого приложения в этом сообщении не указаны.
Ранее обсуждался OPPO CPH2701 / Android16.

Основа ветки: KVN main `d3dcd0642bf368a4c326e6346980083c949829a7`.
Работа PR #2 не объединялась и не изменялась. Действующий NaïveProxy+Caddy сервер
и его настройки не менялись. Старый NET12 не трактуется как доказательство
отсутствия интернета у других UID.

## Выбор архитектуры

Выбран связанный перенос компонентов в KVN: весь libcore, host/distro,
платформенная Go-обвязка, TUN/TCP, Cronet, мониторинг интерфейсов и владение FD.
Android VpnService адаптирован к соответствующему API. Полный UI Husi не переносился.

Полная основа приложения Husi тесно связана с BaseService, KMP, gRPC-репозиторием,
DataStore, базой профилей и многочисленными настройками. Удаление этих связей
затрагивало бы больше кода. Связанный перенос сохраняет действующие сетевые
реализации и существующие экран/Keystore KVN. Это не простая замена AAR и не
переименование Husi. KVN — независимый проект без официальной связи с авторами.

## Зафиксированные исходники и инструменты

| Компонент | Версия / коммит |
|---|---|
| Husi v2.3.4 | `0822cc7b34ae3c57ee2840d553ca8cf31cf64867` |
| Сравнённый dev | `de2176416af298d50bce4e160b0eeb6093ae61f9` |
| sing-box | `v1.15.0-alpha.13`, зависимость официального релиза Husi |
| sing-tun | `v0.9.7-0.20261023115453-611d83b7a57b` |
| SagerNet gomobile/gobind | `v0.1.13` |
| cronet-go / all | `v0.0.0-20261023060337-b3c2ed82ac97` |
| Cronet Android binary | `v0.0.0-20261023055542-d5437a0df01a` |
| protect-go | `v0.2.0` |
| Go / Android NDK | `1.27.0` / `29.0.14206865` |
| Java / SDK / build tools | `17` / `35` / `35.0.0` |
| Android command-line tools | `13114758` |
| Kotlin / AGP / Gradle | `2.1.10` / `8.10.1` / `8.13` |
| Python / fixture TLS tooling | `3.12` / OpenSSL из Ubuntu24.04 |

Полный граф и суммы — `native/husi/libcore/go.mod` и `go.sum`. Зависимости не
обновлялись на dev. Husi buildScript использует Go1.27.3/NDK30; KVN сохраняет
указанные выше проверенные CI версии. Побитовое совпадение с upstream APK не
заявляется. Java/Kotlin/AGP существующего KVN сохранены.

В dev изменены DNS message.Copy().Pack, MPTCP default и Linux bypass внешних
плагинов. Эти изменения не переносились в выбранный release baseline.

## Мост и сеть

Gomobile собирает ARM64/x86_64 AAR из собственных исходников, с upstream feature
build tags и Java package `fr.husi.libcore`. Старый `io.nekohasekai.libbox` удалён.
`PlatformInterface.openTun()` не получает TunOptions; Android Builder и конфиг
согласованы. Go дублирует FD через oscall.Dup, Kotlin сохраняет исходный FD.
Две небольшие Go-модификации: StartConfig/CheckKvnConfig без БД профилей/protobuf
и распространение protect=false как ошибки также из platform_box.go.

На Android включены полные IPv4/IPv6 маршруты, без исключений приложений/LAN и
allowBypass. Исходящие транспортные сокеты защищает настоящий protect(fd).
Монитор учитывает LinkProperties и смену физических сетей.

NaiveBean default — HTTPS443/TLS, UDP-over-TCP=false. Distro регистрирует встроенный
sing-box Naive outbound с Chromium Cronet. Внешний плагин не запускается.
HTTP/2 CONNECT мультиплексирует TCP-запросы; insecure_concurrency не увеличивался.
QUIC к прокси выключен. DNS53 перехватывается до общего UDP reject; остальной UDP
отклоняется, TCP fallback проверяется. Совместимость Caddy с sing-box UoT не
предполагается; UoT не включён.

FakeDNS повторяет правило Husi для A/AAAA из TUN: диапазоны 198.51.100.0/24 и
2001:2::/48, постоянный cache mappings. Sing-box восстанавливает FQDN перед
Naive dial, позволяя серверу выбирать адрес сайта. Неадресные DNS-запросы идут
через DoH внутри прокси. Bootstrap только имени VDS выполняется физической
Android Network до TUN; сохраняются A и AAAA. Прямого DNS fallback нет.

## Сравнение с прежним main

| Область | Прежний KVN | KVN 0.2.0 |
|---|---|---|
| Ядро | готовый libbox1.14.1 AAR | исходники Husi libcore v2.3.4 |
| Android API | openTun(TunOptions), CommandServer | openTun(), Husi Service/Host |
| TCP stack | gVisor | Go — фактический default pinned sing-tun |
| MTU | 1500 | 9000 — default Husi |
| DNS назначения | реальные IP | FakeDNS с восстановлением FQDN |
| NET12 self-probe | отключает VPN | нейтральный TUN, opt-in проверка |
| Остановка ядра | TUN закрывается | TUN удерживается до отключения |

Перенесён связанный набор. Успех нельзя причинно приписать только MTU, FakeDNS
или TCP stack без дополнительных независимых A/B испытаний.

## Интерфейс, состояние, секреты

KVN, большая кнопка, состояние, сервер, настройки только host/login/password.
Порт/TLS/протокол заданы автоматически. Все данные профиля шифруются AES256-GCM
с ключом Android Keystore, backup отключён. Изменение сервера требует отключения,
чтобы на экране не отображался новый адрес вместо фактически подключённого.

Отключено → Подключение → Туннель создан → Соединение проверено / Ошибка.
Успех opt-in HTTPS требует реального полного ответа. Создание FD не подтверждает
интернет; EPERM собственного процесса не отключает остальные приложения.
Проверка выбирает VPN по owner UID/адресу TUN; устаревшие результаты отбрасываются.
Долгое нажатие KVN открывает диагностику. Epoch службы предотвращает перезапись
состояния новой службы завершающимся старым экземпляром.

Нативное protocol logging выключено. Логи/реквизиты не отправляются в телеметрию.
При ошибке instance служба удерживает исходный TUN; direct outbound отсутствует.
При смерти всего процесса Android может удалить VPN — для защиты в этом случае
используются системные Always-on VPN и Block connections without VPN.

## Проверки

[Полностью успешный Actions #41](https://github.com/ladokod/kvn/actions/runs/42480047568)
проверил функциональный перенос до изменения номера версии на release0.2.0.
Финальная release-сборка дополнительно проверяет подпись APK и source provenance.

- Собственный core ARM64/x86_64, Kotlin/JNI/Compose, APK: PASS.
- 20 JVM-тестов KVN и 10 probe: PASS.
- Go tests simpleproxyurl/httpfetch/pluginpool: PASS; oscall без тестов.
- 6 инструментальных тестов на Android16/API36: PASS.
- Настоящий production Builder/bridge, другой Linux UID генерирует весь трафик.
  Не используются реквизиты VDS. Локальный Naive/H2 server собирается из тех же
  pinned исходников; только стенд переводит synthetic destinations в loopback.
- MTU9000/1500/1280: HTML/CSS/PNG, SHA256 двух 2MiB ответов IPv4/IPv6,
  16 параллельных HTTPS, TLS hostname rejection, усечённый ответ, UDP443 reject
  обоих семейств, TCP после reject, TXT DNS→DoH через proxy.
- Start/stop/reconnect, реальные protect callbacks, TUN при core stop, неверный
  пароль без fallback, отсутствие plaintext/base64 реквизитов в native log/logcat.
- Server counters: 6 больших ответов (12MiB), 48 параллельных ответов (12MiB),
  3 HTML, 3 PNG, 6 CSS, 3 усечённых ответа, 3 TXT DNS-запроса.
- Пользователь подтвердил работу новой сборки на физическом телефоне.

API36/reference fixture не заменяет реальный Caddy/CDN. Длительные фоновые сессии,
IPv6-only/NAT64 при смене сети, другие OEM и отдельные UDP-приложения не считаются
полностью покрытыми этим набором. Подробный необязательный телефонный checklist
находится в PHONE-TEST-RU.md.

## Лицензии и распространение

GPL-3.0-or-later; Husi/libbox authorship и запрет подразумеваемой связи сохранены.
APK называется KVN. Collector читает build info реального libgojni.so и сохраняет
notices и точные source zip 108 вошедших Go-модулей. Go runtime BSD и Apache2
тексты включены; Android runtime NOTICE/LICENSE ресурсы собираются из реально
разрешённых AAR/JAR, включая classes.jar, с version inventory.

Binary Cronet `d5437a0df01aaebf35062316a35b29dd389f760a` собран из
`fa319c4f3b554bd7c647c6e0f18a7195ac1497a6`; gitlink NaiveProxy:
`be1be963131154ba82761a7ed85ba426b2027319`, Chromium154.0.8037.49.
Включены 70 notice/README.chromium файлов точного дерева и BSD protobuf35.1
(версия7035001 в common.h). Полный 53MiB исходный архив содержит также изменённые
NSPR файлы под MPL2.0. Набор notices — консервативный source-tree superset, не
утверждение, что каждый перечисленный компонент связан с Android APK.

Релиз содержит соответствующие исходники KVN/зависимостей, notices и SHA256.
APK release-варианта не debuggable и не содержит debug AuditVpnService или fixture
CA/реквизитов. Подпись пока использует временный CI-сертификат; постоянный закрытый
production signing key не добавлялся в репозиторий. При несовпадении подписи
предыдущую тестовую сборку нужно переустановить с повторным вводом профиля.
