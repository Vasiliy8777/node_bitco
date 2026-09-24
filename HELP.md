# Getting Started

## Запуск Bitcoin-ноды и майнинга

- [Актуальный план после интеграции BIP130/BIP152](docs/readiness-update-840faf4.md).

- [Аудит готовности: консенсус, политики, P2P и условия начала майнинга](docs/readiness-audit.md).

- [Синхронизация, конфигурация ноды и mining RPC](docs/mining-node.md).
- [Подключение solo-майнеров через Stratum V1](docs/stratum.md): авторизация,
  payout script, автоматическая сложность shares (vardiff) и version rolling.
- [Реализованные проверки консенсуса и ограничения](docs/consensus-validation.md).

RPC и Stratum включаются отдельно и по умолчанию выключены. Для Stratum нужны
пароль и hex `payout-script`; задания выдаются после синхронизации ноды.
Примеры конфигурации и команды тестирования приведены в документах выше.
Проверки с Bitcoin Core выполняются в отдельном временном regtest-каталоге.
Поддержка протокола пока не подтверждает совместимость с конкретной прошивкой ASIC.

### Reference Documentation

For further reference, please consider the following sections:

* [Official Apache Maven documentation](https://maven.apache.org/guides/index.html)
* [Spring Boot Maven Plugin Reference Guide](https://docs.spring.io/spring-boot/4.1.1/maven-plugin)
* [Create an OCI image](https://docs.spring.io/spring-boot/4.1.1/maven-plugin/build-image.html)

### Maven Parent overrides

Due to Maven's design, elements are inherited from the parent POM to the project POM.
While most of the inheritance is fine, it also inherits unwanted elements like `<license>` and `<developers>` from the
parent.
To prevent this, the project POM contains empty overrides for these elements.
If you manually switch to a different parent and actually want the inheritance, you need to remove those overrides.
