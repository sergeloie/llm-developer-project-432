# JMeter-сценарий проверки product-card-ai

Файл `document-pipeline.jmx` проверяет все поддерживаемые входные документы из `data/`:

1. `/health`
2. `/ready`
3. загрузка каждого документа через `POST /documents/`
4. повторная загрузка того же файла (путь `already indexed`)
5. получение метаданных документа через `GET /documents/{id}`
6. запуск генерации карточки через `POST /generate-card`
7. чтение статуса задачи через `GET /jobs/{id}`
8. статус temporal workflow через `GET /workflows/{id}`
9. синхронная генерация через `POST /cards`
10. негативный кейс загрузки неподдерживаемого расширения

## Запуск

```bash
cd llm-developer-project-432
make infra
make setup
make api      # terminal 1
make worker   # terminal 2
jmeter -n -t tests/jmeter/document-pipeline.jmx -l jmeter.jtl -e -o jmeter-report
```

CSV `documents.csv` задаёт список файлов, которые будут обработаны. Пути должны быть относительны рабочей директории проекта.

## Ограничения

- сценарий предполагает, что приложение и temporal worker запущены;
- асинхронный workflow может завершаться после паузы JMeter, поэтому проверяются в основном HTTP-контракты и стартовые ответы;
- для проверки oversized/empty сценариев нужно добавить соответствующие файлы в каталог `tests/jmeter` и отдельные HTTP-запросы в план.