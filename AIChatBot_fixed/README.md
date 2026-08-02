# Groq AI Chatbot

A minimal full-stack chatbot: a plain HTML/JS frontend talking to a Java
backend (`com.sun.net.httpserver`) that proxies requests to the
[Groq](https://groq.com) LLM API (`llama-3.1-8b-instant`).

## Project structure
```
AIChatBot/
├── src/GroqChatBot.java   # backend server
├── public/index.html      # frontend chat UI
├── .env.example           # template for your API key (copy to .env)
└── .gitignore
```

## Setup

1. **Get a Groq API key** from https://console.groq.com/keys

2. **Create your `.env` file** (this file is git-ignored, never commit it):
   ```
   cp .env.example .env
   ```
   Then edit `.env` and paste your real key:
   ```
   GROQ_API_KEY=gsk_your_actual_key_here
   ```

3. **Compile and run the backend** (from the project root, so it can find `.env`):
   ```
   javac src/GroqChatBot.java -d bin
   cd bin
   cp ../.env .
   java GroqChatBot
   ```
   You should see:
   ```
   Server running at http://localhost:8080/chat
   ```

4. **Open the frontend** — just open `public/index.html` in a browser
   (double-click it, or serve it with any static file server). The backend
   sends CORS headers, so this works even though the page and the API
   aren't on the same origin.

## How it works
- The frontend POSTs your message as plain text to `http://localhost:8080/chat`.
- The Java backend reads `GROQ_API_KEY` from `.env` at startup, forwards your
  message to Groq's chat-completions endpoint, and extracts the reply text
  from the JSON response.
- If the key is missing, the server refuses to start and tells you why,
  instead of running and silently failing on every request.

## Notes
- Never commit your real `.env` file — only `.env.example` should be in
  version control.
- If you rotate or regenerate your Groq key, update `.env` and restart the
  server.
