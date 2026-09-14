<h1 align="center">ITMO.Widgets Backend</h1>

<p align="center">
  <strong>Бэкенд для приложения <a href="https://github.com/alllexey-dev/ITMO.Widgets">ITMO.Widgets</a></strong>
</p>

**ITMO.Widgets Backend** — бэкенд для уникальных функций приложения ITMO.Widgets.<br>
Проект использует <a href="https://github.com/alllexey-dev/my-itmo-api">my-itmo-api</a> и <a href="https://github.com/alllexey-dev/itmo-widgets-core">itmo-widgets-core</a>.

<a href="https://github.com/users/alllexey-dev/projects/1"><strong>Roadmap & status </strong></a>

### 🌟 Текущие возможности

* Проверка access-token ITMO.ID; пользовательский refresh-token на Backend не сохраняется
* Профили, взаимная дружба и независимая приватность расписания/спорта
* Регистрация устройств через Google Firebase
* Очереди спорта и best-effort уведомления устройств через FCM (без гарантии доставки или записи):
  * Free-sign: команда устройству попробовать запись при освобождении места
  * Auto-sign: команда устройству попробовать запись при появлении прогнозируемого занятия

### 🛠️ Зависимости

* `itmo-widgets-core`
* `my-itmo-api`
* `Spring Boot`
* `Spring Security`
* `Auth0 java-jwt`

### 🚀 Использование

Реализация модели и методов API для клиента доступна в <a href="https://github.com/alllexey-dev/itmo-widgets-core">itmo-widgets-core</a>.

### База данных и развёртывание

Новая установка использует PostgreSQL 17 и Flyway; Hibernate только проверяет
схему. Переход с MariaDB запланирован с пустой базой после выпуска v2.1, без
переноса пользовательских данных. Подготовка к переходу не очищает существующие
окружения и не является разрешением на развёртывание.

Backend рассчитан ровно на один экземпляр: планировщики не берут распределённую
блокировку, а фаза чередования уведомлений хранится в памяти процесса. Вторая
реплика даст дубли уведомлений, поэтому сервис в `deploy/compose.yaml`
масштабировать нельзя. Подробности — в разделе single instance
[очередей спорта](docs/sport-automation.md).

См. [локальный запуск, миграции, безопасное переключение и откат](docs/database.md),
[контракт приватности](docs/privacy.md) и [очереди спорта и доставка уведомлений](docs/sport-automation.md).
Шаблоны Docker Compose и переменных окружения находятся в `deploy/`.

Текущий согласованный dev-контракт: Backend/Core `1.2.0-SNAPSHOT`, Android
`2.1-SNAPSHOT`. Core опубликован только локально для проверки; публичной
публикации и развёртывания эти изменения не выполняют. Старый нерелизный boolean
privacy API удалён; приложение читает viewer-scoped `capabilities`, собственные
настройки — через `/api/users/me/privacy`. Legacy `/api/app/version` сохранён
наряду с `/api/app/version-info`; min/latest по умолчанию остаются `2.1`.

Технический refresh-token сохраняется независимо от rollback каталога. Обновления
спорта имеют безопасные `SUCCESS`/`PARTIAL`/`FAILED` итоги; старые технические
журналы очищаются через 90 дней без удаления пользовательской истории. Наличие
HTTP-ответа само по себе не подтверждает готовность upstream MyITMO.
