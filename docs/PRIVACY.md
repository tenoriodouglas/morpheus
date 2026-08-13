# Política de Privacidade — Morpheus (Controle Parental)

_Última atualização: 2026-08-13_

O Morpheus é um app de **controle parental transparente**. O aparelho da criança
exibe permanentemente um aviso de que é gerenciado. O app **não** grava áudio,
câmera ou tela de forma oculta.

## Dados coletados (modo Filho) e finalidade
- **Código de pareamento** (aleatório): vincular o aparelho do filho ao do responsável.
- **Política de uso** (horários, regras de apps, limites): recebida do responsável para aplicar os bloqueios.
- **Localização aproximada/precisa**: exibir a última posição para o responsável e alertas de geofence. Coletada apenas com a permissão concedida no aparelho.
- **Resumo de uso de apps** (minutos por app, no dia): relatório de tempo de tela para o responsável.
- **Sinal de atividade (heartbeat)** e **alertas** (ex.: possível alteração da hora): mostrar status de conexão e segurança ao responsável.

## Onde os dados ficam
Os dados são sincronizados via **Google Firebase (Cloud Firestore + Authentication)**,
na conta Firebase do responsável que publica o app. Não há outro servidor. O acesso
é restrito aos dispositivos pareados (autenticação anônima + regras de segurança por
membros).

## Retenção e exclusão
Os dados permanecem enquanto o pareamento existir. Ao **remover a proteção** ou
desinstalar, o responsável pode excluir o documento do filho no Firestore.

## O que NÃO fazemos
- Não capturamos microfone, câmera ou tela de forma oculta.
- Não vendemos nem compartilhamos dados com terceiros para publicidade.

## Contato
Responsável pela publicação do app (a preencher pelo publicador): _seu e-mail aqui_.

---

# Privacy Policy — Morpheus (Parental Control)

_Last updated: 2026-08-13_

Morpheus is a **transparent parental-control** app. The child device permanently
shows a "managed device" notice. The app does **not** covertly record audio,
camera or screen.

## Data collected (Child mode) and purpose
- **Pairing code** (random): links the child device to the guardian's.
- **Usage policy** (schedules, app rules, limits): received from the guardian to enforce blocks.
- **Approximate/precise location**: show the last position to the guardian and geofence alerts. Collected only with the permission granted on the device.
- **App usage summary** (minutes per app, per day): screen-time report for the guardian.
- **Heartbeat** and **alerts** (e.g. possible clock tampering): show connection and safety status to the guardian.

## Where data lives
Data is synced via **Google Firebase (Cloud Firestore + Authentication)** in the
Firebase account of the guardian who publishes the app. There is no other server.
Access is restricted to the paired devices (anonymous auth + membership rules).

## Retention and deletion
Data remains while the pairing exists. On **remove protection** or uninstall, the
guardian can delete the child's Firestore document.

## What we do NOT do
- We do not capture microphone, camera or screen covertly.
- We do not sell or share data with third parties for advertising.

## Contact
App publisher (to be filled by the publisher): _your email here_.
