# Qwen Coder Native v3.0

Нативный Android-клиент Qwen Coder (coder.qwen.ai) с чатом в стиле Claude / ChatGPT. Работает напрямую с API сервиса, без WebView.

## Возможности
- Список задач (боковая панель), открытие истории, удаление (долгое нажатие).
- Новая задача: выбор репозитория из подключённых в Qwen Coder, ветки и модели (по умолчанию `qwen3-coder-plus`).
- Чат со стримингом ответа (SSE), блоки кода с копированием, свёрнутые карточки действий агента (Shell, правки файлов и т.д.) с выводом команд.
- Голосовой ввод (ru-RU), открытие превью проекта и страницы задачи в браузере.

## Вход
Нужен `refresh_token` с coder.qwen.ai: в Chrome на компьютере F12 → Application → Cookies → `.qwen.ai` → `refresh_token` → Value. Вставьте значение в приложение (подойдёт и строка Cookie целиком). Приложение само обновляет короткоживущий access token и сохраняет новый refresh token. Токены хранятся только на телефоне.

Есть и вход через встроенный браузер (email), но Google OAuth в WebView обычно блокируется.

## API (по перехвату трафика)
- `auth.qwen.ai/api/v2/auths/refresh` (cookie `refresh_token`) → `access_token` + новый `refresh_token`
- `coder.qwen.ai/coder/api/v2/`: `task/list`, `task/{id}`, `task/{id}/status`, `task/new`, `task/completions?chat_id=` (SSE), `environments/`
- Не удалось увидеть в перехвате: остановка задачи на сервере, добавление нового репозитория. Для этого есть ссылка на сайт.
