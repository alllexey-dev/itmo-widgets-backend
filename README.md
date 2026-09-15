<h1 align="center">ITMO.Widgets Backend</h1>

<p align="center">
  <strong>Бэкенд для приложения <a href="https://github.com/alllexey-dev/ITMO.Widgets">ITMO.Widgets</a></strong>
</p>

Сервер отвечает за то, чего нет в MyITMO: пользователей и друзей, приватность
расписания и спорта, очереди автозаписи на спорт и push-уведомления. Все
данные университета приложение получает напрямую через
[my-itmo-api](https://github.com/alllexey-dev/my-itmo-api); клиентский контракт
сервера лежит в [itmo-widgets-core](https://github.com/alllexey-dev/itmo-widgets-core).

## Возможности

- Аутентификация по access-token ITMO.ID; refresh-token пользователя на сервер
  не попадает и не хранится.
- Явные заявки в друзья, публичные профили, поиск зарегистрированных по ИСУ.
- Независимая приватность расписания и спорта: все, друзья или никто.
- Очереди спорта: запись при освобождении места и на прогнозируемое занятие,
  с командами устройству через FCM. Доставка best-effort, без гарантии записи.
- Уведомления о заявках в друзья и их принятии.
- Регистрация устройств и метаданные версии приложения.

## Стек

Kotlin, Spring Boot, Spring Security, Spring Data JPA, PostgreSQL 17, Flyway,
Firebase Admin, Java 21. Ровно один экземпляр: планировщики не берут
распределённую блокировку.

## Запуск и тесты

```bash
DOCKER_HOST=unix://$HOME/.colima/default/docker.sock TESTCONTAINERS_RYUK_DISABLED=true \
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew build
```

Тесты поднимают одноразовый PostgreSQL через Testcontainers. Локальный запуск,
переменные окружения и миграции описаны в [docs/ops/database.md](docs/ops/database.md).

## Документация

- [docs/README.md](docs/README.md) — индекс: контракты API, база данных,
  деплой и журнал деплоев.
- [AGENTS.md](AGENTS.md) — правила для агентов.
- [CHANGELOG.md](CHANGELOG.md) — история изменений.
