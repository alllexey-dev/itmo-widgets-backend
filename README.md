<h1 align="center">ITMO.Widgets Backend</h1>

<p align="center">
  <strong>Бэкенд для приложения <a href="https://github.com/alllexey-dev/ITMO.Widgets">ITMO.Widgets</a></strong>
</p>

**ITMO.Widgets Backend** — бэкенд для уникальных функций приложения ITMO.Widgets.<br>
Проект использует <a href="https://github.com/alllexey-dev/my-itmo-api">my-itmo-api</a> и <a href="https://github.com/alllexey-dev/itmo-widgets-core">itmo-widgets-core</a>.


<a href="https://github.com/users/alllexey-dev/projects/1"><strong>Roadmap & status </strong></a>

### 🌟 Текущие возможности
* **Полностью локальная** аутентификация по access-token ITMO.ID
* Авторизация через JWT (access + refresh)
* Регистрация устройств через Google Firebase
* Уведомления о появлении новой записи на спорт, соответствующей фильтрам (не используется на данный момент)
* Очередь автозаписи на спорт и уведомление устройств о появлении мест через FCM:
* * Free-sign: запись при освобождении места на существующее занятие
* * Auto-sign: запись сразу при появлении прогнозируемого занятия

### 🛠️ Зависимости
* `itmo-widgets-core`
* `my-itmo-api`
* `Spring Boot`
* `Spring Security`
* `JJWT`

### 🚀 Использование
Реализация модели и методов API для клиента доступна в <a href="https://github.com/alllexey-dev/itmo-widgets-core">itmo-widgets-core</a>.
### База данных и развёртывание

Новая установка использует PostgreSQL 17 и Flyway; Hibernate только проверяет
схему. Переход с MariaDB запланирован с пустой базой после выпуска v2.1, без
переноса пользовательских данных. Подготовка к переходу не очищает существующие
окружения и не является разрешением на развёртывание.

См. [локальный запуск, миграции, безопасное переключение и откат](docs/database.md).
Шаблоны Docker Compose и переменных окружения находятся в `deploy/`.
