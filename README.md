<p align="center">
  <img src="docs/images/icon.png" width="96" alt="Иконка ITMO.Widgets" />
</p>

<h1 align="center">ITMO.Widgets Backend</h1>

<p align="center">
  <strong>Сервер приложения ITMO.Widgets</strong>
  <br />
  Друзья, приватность, очереди на спорт, отзывы и подключённые устройства
</p>

<p align="center">
  <a href="https://github.com/alllexey-dev/itmo-widgets-backend/releases"><img src="https://img.shields.io/github/v/release/alllexey-dev/itmo-widgets-backend?style=flat-square&color=blue" alt="Release" /></a>
  <img src="https://img.shields.io/badge/Java-21-orange?style=flat-square&logo=openjdk&logoColor=white" alt="Java 21" />
  <img src="https://img.shields.io/badge/PostgreSQL-17-4169E1?style=flat-square&logo=postgresql&logoColor=white" alt="PostgreSQL 17" />
</p>

<p align="center">
  <a href="docs/README.md"><strong>Документация</strong></a> ·
  <a href="docs/contracts">Контракты API</a> ·
  <a href="docs/openapi.json">OpenAPI</a> ·
  <a href="CHANGELOG.md">История изменений</a>
</p>

Сервер [ITMO.Widgets](https://github.com/alllexey-dev/ITMO.Widgets) отвечает за то, чего нет в My ITMO:
пользователей и друзей, приватность, очереди автозаписи на спорт, ссылки к предметам, отзывы о
преподавателях, модерацию, веб-версию и push-уведомления. Данные университета приложение получает
напрямую через [my-itmo-api](https://github.com/alllexey-dev/my-itmo-api).

Ставить ничего не нужно: приложение уже работает с сервером на `widgets.alllexey.dev`. Репозиторий
открыт для тех, кто хочет посмотреть, что сервер делает и что он хранит, или помочь с разработкой.

## Что умеет

**Аккаунт и друзья**

- Вход по access-токену ITMO.ID. Refresh-токен пользователя на сервер не попадает и не хранится.
- Заявки в друзья, публичные профили, поиск зарегистрированных по ИСУ, уведомления о заявках и их
  принятии.
- Приватность расписания, спорта и списка друзей настраивается отдельно: все, друзья или никто.

**Спорт**

- Очереди: запись, когда место освободилось, и запись на прогнозируемое занятие. Команду записаться
  получает телефон (push-уведомлением) или [агент](https://github.com/alllexey-dev/itmo-widgets-agent).
  Запись не гарантируется: команда может не дойти, а место могут занять раньше.

**Предметы и преподаватели**

- Ссылки к предметам: личные, видимые своему потоку или всем, с премодерацией, голосами и жалобами.
- Отзывы о преподавателях: свои отзывы с премодерацией и проверкой по ИСУ, что преподаватель вёл
  занятия у автора, копии отзывов проекта Reviews, голоса, жалобы и ИИ-сводки через Gemini.

**Подключённые устройства и веб-версия**

- Веб-версия, агент и MCP-сервер подключаются как устройства: вход подтверждается с телефона, у каждого
  подключения свой токен и свой набор доступов.
- Админка веб-версии: сводка и статистика, пользователи и роли, модерация, отзывы и сводки, версия
  приложения, служебные учётные данные, журнал действий.

**Модерация**

- Очередь жалоб и материалов на проверке (ссылки, отзывы), решения, ограничения пользователей и роли
  модераторов.

## Экосистема

| Репозиторий | Что это |
|---|---|
| [ITMO.Widgets](https://github.com/alllexey-dev/ITMO.Widgets) | Приложение для Android и iPhone |
| **itmo-widgets-backend** (этот репозиторий) | Сервер: друзья, приватность, очереди на спорт, отзывы, уведомления |
| [itmo-widgets-web](https://github.com/alllexey-dev/itmo-widgets-web) | Сайт и веб-версия |
| [itmo-widgets-agent](https://github.com/alllexey-dev/itmo-widgets-agent) | Агент для компьютера или сервера: автозапись на спорт без телефона |
| [itmo-mcp](https://github.com/alllexey-dev/itmo-mcp) | MCP-сервер ИТМО для Claude, Codex, Cursor и других ассистентов |
| [my-itmo-api](https://github.com/alllexey-dev/my-itmo-api) | Библиотека для My ITMO и БАРС: Kotlin Multiplatform и Java |

## Разработчикам

### Запуск

Нужны JDK 21 и Docker: тесты поднимают одноразовый PostgreSQL через Testcontainers.

```bash
./gradlew build      # сборка, все тесты и ktlint
```

Локальный сервер на `http://localhost:8080`:

```bash
cp deploy/.env.example deploy/.env   # задайте POSTGRES_ADMIN_PASSWORD и DB_PASSWORD
docker compose --env-file deploy/.env -p itmowidgets-local -f deploy/compose.local.yaml up -d --wait
DB_URL=jdbc:postgresql://localhost:55432/itmowidgets DB_USER=itmowidgets \
DB_PASSWORD="$(sed -n 's/^DB_PASSWORD=//p' deploy/.env)" ./gradlew bootRun
```

Настройка под colima, остальные переменные окружения и отдельные наборы тестов описаны в
[docs/development.md](docs/development.md).

### Как устроено

Kotlin, Spring Boot, Spring Security, Spring Data JPA, PostgreSQL 17, Flyway, Firebase Admin, Java 21.
Сервер работает ровно в одном экземпляре: планировщики не берут распределённую блокировку.

Типы API принадлежат серверу: контракты лежат в [docs/contracts](docs/contracts), а
[правило совместимости](docs/contracts/compatibility.md) описывает, что можно менять, пока у
пользователей стоят старые версии приложения.

### Документация

- [docs/README.md](docs/README.md): полный индекс. Контракты API, база данных и миграции, модерация,
  удаление аккаунта, деплой.
- [docs/architecture.md](docs/architecture.md): пакеты, слои и куда класть новый маршрут.
- [CONTRIBUTING.md](CONTRIBUTING.md): ветки, PR, релизы и миграции.
- [CHANGELOG.md](CHANGELOG.md): история изменений.

## Обратная связь

Ошибки и идеи: [форма обратной связи](https://fb.widgets.alllexey.dev),
[Issues](https://github.com/alllexey-dev/itmo-widgets-backend/issues) или
[@itmowidgets](https://t.me/itmowidgets).

---

Неофициальный проект, не связан с Университетом ИТМО.
