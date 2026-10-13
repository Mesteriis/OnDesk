# Проверка OnDesk — 2026-10-12

## Отправка ссылок — 0.3.0

- Swift: 19 тестов прошли. Новые проверки: HTTP/HTTPS validation, запрет прочих
  схем и credentials, повтор после перезапуска без повторного открытия, привязка
  UUID к владельцу и URL, неопределённый результат после сбоя, отказ браузера.
  Настоящий TLS: сопряжённый клиент отправил open_link, повторил на новом соединении
  после игнорирования первого результата, opener вызван один раз; отзыв доступа
  запретил повтор. В тестах браузер заменён счётчиком принятия URL.
- Android: 12 JVM-тестов прошли, включая извлечение ссылки с названием страницы,
  сохранение query/fragment, отказы для неподдерживаемого/неоднозначного текста,
  формирование open_link и обязательное подтверждение opened.
  Неверный percent escape сначала дал URISyntaxException вместо ожидаемого отказа;
  после нормализации ошибки регрессия прошла.
- Android 17: 2 instrumentation-проверки прошли на финальном APK: ACTION_SEND
  text/plain создаёт запись ссылки в Room, её удаление не требует файла;
  миграция Room 1 → 2 сохранила существующие path, offset, serverID и добавила link=NULL.
- Эмулятор Android 17: ссылка на недоступный QA Mac осталась в очереди после
  force-stop и повторного запуска; URL наблюдался в UI очереди после перезапуска.
- Финальный APK 0.3.0 установлен на Samsung Z Fold7 без удаления данных;
  настройка службы скриншотов сохранилась. Mac .app 0.3.0 собран и ad-hoc подпись проверена.
- Физический Z Fold7 → Mac: настоящий ACTION_SEND text/plain передал публичный
  тестовый URL. Mac сохранил opened receipt; стандартным обработчиком HTTPS является
  Chrome, адрес активной вкладки точно совпал с тестовым URL (проверено Apple Events).
  CUA для Chrome оказался недоступен, native capture завершился ошибкой;
  проверка вкладки выполнена через Apple Events, без чтения остальных вкладок.
- Android lint: 0 ошибок. Физическое выключение Wi-Fi и аварийное завершение Mac
  внутри запуска браузера не выполнялись; соответствующая неопределённая стадия
  проверена тестом хранилища. Подтверждение открытия не доказывает загрузку сайта.

## Выполнено

- Xcode 27 / Swift 6.4: приложение собирается; `.app` упаковано и ad-hoc подписано.
- Swift: 14 тестов прошли. Проверены категории, безопасные имена, принадлежность
  передачи телефону, SHA-256, пустой файл, недоступная папка, совпадения имён,
  потеря неподтверждённого хвоста, восстановление публикации без дубликата,
  одноразовость/срок QR, отзыв доступа и реальные TLS-запросы NWConnection.
- Android: 10 JVM-тестов прошли; debug APK и instrumentation APK собраны.
- Чистые эмуляторы Android 16 и 17: настоящий ACTION_SEND_MULTIPLE с внешним
  тестовым ContentProvider добавил TXT и PNG в Room и внутреннее хранилище.
- Android 16 и 17: force-stop между приёмом файлов и сопряжением; очередь сохранилась.
  Неверный TLS pin отклонён, правильный принят; сопряжение сохранилось через Keystore;
  настоящий WorkManager передал оба файла в Mac-приёмник и очистил подтверждённые копии.
- На Mac TXT имеет SHA-256 `ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad`;
  PNG совпал с исходным тестовым изображением, категории documents/images верны.
- Иконки проверены в APK через aapt2; Android UI и launcher отображают исходный знак.

- Android 17: синтетический файл 4 ГиБ + 17 байт передан за 280,59 с с SHA-256
  на приёмнике, продолжением после первого блока 4 МиБ и повторным finish без дубликата.
  MIME video/mp4; это проверка транспорта, файл не является воспроизводимым видео.
- Android 16 и 17: удаление ожидающего файла и импорт при занятом sender прошли
  (по 2 instrumentation-теста). Регрессия импорта сначала упала на Android 16,
  после разделения блокировок импорта/отправки прошла.
- Android lint: 0 ошибок; предупреждения о версиях зависимостей, намеренном pin trust
  manager и синхронных SharedPreferences commit требуют отдельной оценки.

## Физические устройства

- Samsung Z Fold7 / Android 17: APK установлен и обновлён без удаления данных;
  сканирование QR завершилось сопряжением, телефон отображается в настройках Mac.
- Пользователь отправил изображение через системное «Поделиться» → OnDesk.
  Получен один завершённый файл в категории «Изображения»; размер и SHA-256
  совпали с метаданными. В spool телефона осталось 0 .ready и 0 .part.
- Нативный UI Mac проверен через CUA: настройки, QR, подключённый телефон,
  статус сохранённого файла. Предыдущий timeout больше не воспроизводится при открытом окне.
- Android scanner: manifest-регрессия воспроизвела sensorLandscape (FAIL), после
  override на unspecified прошла (PASS). Исправленный APK установлен на телефон.
  Повторная физическая проверка ориентации камеры ещё не выполнена.

## Не подтверждено

- Bonjour/NSD при реальном изменении IP, длительные ограничения фона Samsung,
  перезагрузка телефона/Mac и передача воспроизводимого большого видео на физическом телефоне.
- Системные диалоги macOS и запуск при входе.
- Реальное заполнение диска не выполнялось; тестируется отказ выбранного назначения.
- Разрыв TCP проверяется отдельно от выключения физического Wi-Fi и перезагрузки Mac.

## Быстрые действия Android 0.2.0

- Debug APK собран, 10 JVM-тестов прошли; lint завершился без ошибок.
- Android 17, собственный эмулятор: 7 различных instrumentation-проверок прошли.
  Проверены опубликованные шорткаты, единственная capability службы — screenshot,
  импорт без второй копии, идемпотентность, отказ для файла вне captures,
  реальный захват экрана после закрытия activity, результат камеры и отмена с очисткой.
  Результат камеры в этих тестах синтетический; захват экрана использует Android API.
- Регрессия screenshot сначала не создавала запись очереди: finish в onCreate
  не давал ожидаемый onStop. Перенос finish в onResume прошёл проверку.
- Samsung Z Fold7 / Android 17: 0.2.0 установлен с сохранением сопряжения.
  Пользователь вручную включил службу, запустил оба действия из OnDesk и подтвердил
  получение скриншота и фото на Mac. Приёмник сохранил PNG и JPEG в «Изображениях»;
  размер и SHA-256 каждого совпали с метаданными, обе передачи завершены.
  После подтверждений каталог captures на телефоне пуст.
- Отказ разрешения камеры, заблокированный телефон,
  защищённое окно и восстановление незавершённой съёмки после гибели процесса
  пока не проверены на физическом устройстве.

### Настройка Touch the Notch на физическом Z Fold7

- Установленный `com.notch.touch` предлагает только запуск приложения, без выбора
  шорткатов/activity. Добавлен необязательный модуль quick-launcher с двумя APK
  без разрешений и зависимостей; каждый запускает фиксированную activity OnDesk.
- Обе сборки и lint прошли, 10 JVM-тестов основного приложения прошли.
  Пускатели установлены; оба названия реально появились в списке Touch the Notch.
- Через UI назначены двойное нажатие → OnDesk — Скриншот, свайп справа налево →
  OnDesk — Камера. Занятое ранее долгое нажатие сохранено.
- ADB-ввод двойного нажатия в фактическую область overlay Touch the Notch создал
  ещё один PNG на Mac; завершение, размер и SHA-256 проверены. ADB-свайп запустил
  пускатель камеры → CameraShortcutActivity → Samsung camera с IMAGE_CAPTURE.
  Камера отменена без съёмки, captures пуст. Это проверка реального приложения
  жестов с инъецированным вводом; удобство жестов пальцем отдельно не оценивалось.

## Найдено и исправлено

Apple Security принимал неканонический DER INTEGER серийного номера, Android
Conscrypt отклонял его с INVALID_INTEGER. Регрессия на фиксированных P256 ключах
воспроизвела ошибку; минимальное положительное INTEGER-кодирование прошло проверку.
Проверка pin и срока сертификата на Android осталась строгой.

Импорт новых файлов больше не ждёт сетевую передачу; отдельная блокировка сохраняет
согласованность импорта и смены Mac. Обновление статуса ошибки сохраняет последний
подтверждённый offset. Android backup/device-transfer явно исключает очередь и токены.

Независимое read-only ревью выполнено: исправлены удаление ожидающего файла при
занятом sender, NSD после чужого сертификата на старом адресе, очистка сирот spool
и ручной повтор без ожидания предыдущего backoff. Повторное ревью проверило первые
три исправления и уточнило политику ручного повтора; используются отдельные одноразовые
работы с общей блокировкой отправителя. JVM-регрессии проверяют безопасную очистку
и NSD fallback без ослабления pin. Назначения без hard links документированы.

Сохранённый большой файл: 4294967313 байт, SHA-256
`d21cd94c214bbd77b12f93a3c155959c2288364784bae289b7fc491bb13bc024`.

## Mac camera hotkey + retention 0.4.0 — 2026-10-12

- Swift suite: 25 tests PASS, including camera ownership, online presence, one pending
  request, launch expiry, cancellation, restart, duplicate finish, authenticated TLS
  polling and copying only the matching completed photo. Receipt authentication and
  SHA-256 file publication retain existing checks.
- Android: 12 JVM tests PASS; debug APK and lint PASS (zero lint errors).
- Android 17 own emulator: QuickActionsTest + LinkShareTest, 11 instrumentation tests
  PASS. Includes remote request UUID in actual persistent queue, private activity
  export boundary, camera result/cancel, screenshot, share links and Room migration.
  Camera results are synthetic in instrumentation, not physical camera evidence.
- Physical Samsung Z Fold7: Mac-injected Cmd+Shift+1 opened Samsung camera via the
  service's authenticated polling. User took/confirmed a test photo and explicitly
  confirmed that Cmd+V pasted the image on Mac. Pasteboard advertised image data;
  last completed photo SHA-256 matched the saved file. Final launch-budget build
  also opened the physical camera. This establishes the unlocked, same-LAN flow.
- Retention RED: empty candidate implementation missed expired image and failed
  invalid-days/missing-folder checks. GREEN: old image only; exact age boundary,
  fresh image, PDF, directory, hidden file and symlink protected. A disposable old
  PNG was actually moved into ~/.Trash, verified recoverable by its unique path,
  then that test fixture alone was removed. No user receive folder was swept;
  cleanup remains disabled by default.
- Long-running battery/Doze behavior, locked-device/expired command physical checks,
  phone/Mac restart during an active capture, and multi-phone UI choice are not
  physically accepted. Automatic hourly scheduling is not a multi-hour soak test.
- Final native settings UI: Folders tab exposes cleanup toggle, 3-day stepper,
  manual-check button and disabled status. Keychain startup work was moved off
  the main actor after a sample showed SecItemCopyMatching blocking settings;
  final receiver TCP listener is live and settings open through the menu.
  Settings activation is explicit before openSettings. User cleanup stayed off.

## Share auto-close — Android 0.4.1, 2026-10-12

- RED on existing 0.4.0: ShareCompletionTest failed all 3 initial checks because
  the activity had already been destroyed after queue import. GREEN tracks one
  share batch and keeps its delivered receipts until the window finishes.
- 12 JVM tests PASS; debug build and lint PASS (zero lint errors).
- Android 17 own emulator: 17 instrumentation tests PASS. Share regression covers
  multi-file completion, unrelated delivery, retained receipt during worker cleanup,
  visible error and successful retry, recreation without duplicate import, manual
  closing with queued bytes retained. Room upgrades 1→3 and 2→3 preserve files/links.
  Existing screenshot, camera and link tests remain green.
- A later focused share test initially missed the error text during UI transition;
  changed the assertion to wait for the visible explanation instead of sampling
  immediately. Final full suite above includes that assertion.
- Physical Samsung: updated to 0.4.1 with data preserved. A disposable text document
  sent by ACTION_SEND reached the running Mac; durable receipt, saved SHA-256 and
  closed share activity verified. The fixture source and destination were removed
  only after verification. A nonexistent private source URI left the share window
  open with the actual friendly copy/access error visible. No user files were used.
- Physical error testing exposed an earlier remote-camera service teardown crash:
  Conscrypt TLS close wrote network bytes on the main thread (NetworkOnMainThreadException).
  RemoteCloseTest RED reproduced forbidden main-thread cleanup using a thread-guarded
  real closeable resource. GREEN dispatches close on IO in application scope, which
  survives service-scope cancellation. Final physical delivery/error checks above
  were repeated with this fix installed.
- No destructive migration or pairing reset. Mac bundle/protocol unchanged.
- Android process death during import and multi-window concurrent shares have not
  been physically exercised; recreation and durable receipt retention are covered
  in instrumentation. Force-stop/discarded activity may leave small delivered
  metadata rows until restored/deleted; acknowledged payload copies are removed.

## Mac notifications — 0.4.1, 2026-10-12

- swift test --package-path macos: 28 tests PASS, zero failures. New coverage:
  notification content/actions receipt resolution, silent/sound payloads, completed
  file requirement, legacy receipt decoding, owner checks, durable duplicate
  suppression after restart, missing files and symlink replacement. Existing TLS
  receiver test now verifies one callback per actual file despite repeated finish.
- scripts/build-macos.sh: debug .app build PASS; ad-hoc codesign and strict signature
  verification PASS. Bundle version 0.4.1 (5); Android/protocol unchanged.
- Native UI exposes notifications and sound toggles, permission status, permission
  request and System Settings link. The link opened the correct macOS pane; enabled
  OnDesk notifications there and observed authorized status in the app.
- Initial live transfer attempt could not run: selected Samsung absent from ADB.
  Updated Mac startup was waiting in SecItemCopyMatching; SecurityAgent interaction
  is blocked by the UI tool. Requested user confirmation in the protected OS dialog
  and a test share. The user subsequently confirmed access and sent a document.
- Live Mac after that share: receiver ready, one completed receipt with durable
  notification claim, and saved file SHA-256 matched the transfer metadata.
  UI tooling cannot bind the closed system Notification Center panel and does not
  support the Fn modifier. Asked the user to leave that panel open; visual
  notification delivery and button activation remained pending UI verification.
- User subsequently confirmed the visible notification and opened the system panel.
  Native accessibility inspection showed the OnDesk file notification with both
  Open and Show in Finder actions. Button activation was not verified: the panel
  no longer contained the notification on the next inspection.


## Invisible Android share — 0.4.2, 2026-10-12

- JVM 12 tests PASS; assembleDebug, assembleDebugAndroidTest and lintDebug PASS.
- First transparent-activity checks exposed a completion signal dependent on Compose
  snapshot dispatch: the queue copy finished but the invisible activity remained.
  Replaced it with StateFlow, then the focused 9 instrumentation checks passed.
- A broad runner invocation inadvertently included CrossPlatformTest, which needs
  its separately prepared staged TLS server, generated pairing fixture and empty
  queue. That run failed 4 of 25 checks; it is not an accepted cross-platform run.
- A later selected run exposed UI timing/cleanup issues in the new source-screen
  check and stale error state. Wait for source UI readiness, serialize fixture
  cleanup and wait for the current error. The screenshot test also exposed that
  UiAutomation suppresses accessibility services by default: use its documented
  FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES and enable the own emulator service
  after runner startup. Physical accessibility settings were not changed.
- Physical Samsung updated with install -r to 0.4.2, preserving pairing/queue.
  A disposable text shared to the new target reached the actual running Mac;
  completed durable receipt and SHA-256 matched. ShareActivity closed and the
  original Settings activity resumed, with no automatic MainActivity navigation.
- Emulator screenshot during a delayed fixture copy showed the original source UI,
  not a queue/send screen. The target has no content view and a transparent window.
- Final selected instrumentation: 19 tests PASS, zero failures (ShareCompletion 6,
  LinkShare 3, QuickActions 9, RemoteClose 1). Covers transparent/no-content window,
  source UI visibility and return before delivery, error notification with queue
  action, rotation, restored durable prefix, links, migrations, screenshot/camera
  shortcuts and IO service cleanup. The final installed APK hash matched the build.
  The disposable physical transfer source/destination were cleaned up after checks.

## Public OSS release — 0.4.2, 2026-10-12

- Release packaging: 28 Swift tests and 12 Android JVM tests PASS; release lint and
  assembleRelease PASS. Release packages use version 0.4.2 (6) on both platforms
  and optional launchers. No new instrumentation or physical installation is
  claimed for this packaging-only change.
- Optimized Mac arm64 bundle: strict codesign verification PASS; ad-hoc signature,
  no Developer ID or notarization. Debug sections stripped before signing; the
  executable/archive contains no local home paths. Bundle metadata and ZIP checked.
- Main APK and both gesture launchers: apksigner verification PASS, correct package
  and version, non-debuggable; MIT/third-party licenses embedded. Dedicated release
  key stored outside the source tree, never included in Git or release assets.
- Release assets SHA-256 checks PASS. Current source candidates scanned for token,
  private-key and local-path patterns; no matches. Ignored build/runtime data are
  excluded. Public history starts from a clean source snapshot; local development
  history remains on its original branch.
- Running Mac and paired physical phone left unchanged. Earlier physical feature
  results above use development builds. Gatekeeper acceptance and installation of
  the new signed release APK on a clean physical phone are not verified.

## GitHub Actions setup — 2026-10-13

- Dedicated GitHub Mac runner registered for OnDesk, separate from the existing
  Gitea service. Initial shell launch failed because the runner installation path
  contained spaces; moved the new runner to a path without spaces. Swift tests and
  optimized Mac bundle then passed in Actions.
- Hosted Android setup first rejected the obsolete `tools` package, then the old
  `platforms;android-37` identifier. Pin command-line tools 22 (download 15859902),
  install `platforms;android-37.0`, and use the 37.0 system image name. Hosted JVM,
  lint and debug/test APK builds passed after these environment corrections.
- First fresh API 36 instrumentation run: 9/10 tests passed; notification test
  timed out because POST_NOTIFICATIONS had not been granted on a clean install.
  The test now explicitly grants/asserts its notification prerequisite. This changes
  instrumentation setup only, not production permission or share behavior.
- actionlint and shellcheck passed. Version guard accepted matching metadata and
  rejected Android version/build-number mismatches and invalid version syntax.
