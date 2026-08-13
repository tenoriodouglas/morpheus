# Morpheus — Controle parental de tempo de tela (Android)

App Android **nativo em Kotlin** para famílias controlarem o horário de uso do
celular. O mesmo APK se instala em dois modos — **Responsável** e **Filho** — e o
responsável define janelas em que a **internet do celular do filho é bloqueada**
(ex.: 22:00 → 06:30), de forma resistente a burla e sem precisar de root.

> **Escopo e ética.** O Morpheus é um controle parental **transparente**: o
> aparelho do filho exibe permanentemente um aviso de que é gerenciado. Ele **não**
> grava áudio, câmera ou tela de forma oculta. Monitoramento secreto (*stalkerware*)
> é proibido pela [política do Google Play](https://support.google.com/googleplay/android-developer/answer/9888379)
> e ilegal em muitos lugares — por isso não faz parte deste projeto.

## Por que Kotlin (e não C++)

Para um app Android real, **Kotlin nativo** é a linguagem mais adequada e
"otimizada": tem acesso total às APIs de sistema necessárias aqui (`VpnService`,
`DevicePolicyManager`/Device Admin, `AlarmManager`, foreground services), compila
para bytecode otimizado pelo ART e resulta em um app leve e orientado a eventos.
C++/NDK só compensa para cálculo pesado (áudio/vídeo/jogos), o que não é o caso.

## Como o bloqueio funciona (à prova de burla, sem root)

| Camada | Papel |
| --- | --- |
| `vpn/BlockingVpnService` | VPN local que captura **todo** o tráfego (rotas `0.0.0.0/0` e `::/0`) e não o encaminha — a internet fica sem saída durante a janela bloqueada. Como o Android roteia todo o tráfego pela VPN ativa, não há app que escape. |
| `schedule/ScheduleEnforcer` | Decide se deve bloquear **agora** e agenda um alarme exato para o próximo limite da janela (event-driven, gasta pouca bateria). |
| `service/GuardianService` | Foreground service que mantém a regra aplicada, mostra o aviso transparente e ouve as mudanças enviadas pelo responsável. |
| `admin/MorpheusDeviceAdminReceiver` | Device Admin: impede a desinstalação enquanto ativo. Em modo **Device Owner**, bloqueia a desinstalação de forma absoluta. |
| `receiver/BootReceiver` | Reaplica tudo após reiniciar o aparelho — reiniciar não burla o bloqueio. |
| `remote/*` (Firebase, opcional) | Canal em tempo real: o responsável muda a regra e o celular do filho recebe na hora. |

### Proteção anti-desinstalação: dois níveis

1. **Device Admin (padrão, sem PC):** o filho ativa em 1 toque no setup. Enquanto
   ativo, o app não pode ser desinstalado pelo fluxo normal — é preciso primeiro
   desativar a administração, o que fica visível e pode ser protegido.
2. **Device Owner (máximo, exige configuração via ADB em aparelho zerado):**
   ```bash
   adb shell dpm set-device-owner com.morpheus.family/.admin.MorpheusDeviceAdminReceiver
   ```
   Nesse modo o app chama `setUninstallBlocked(true)` e a criança **não consegue**
   desinstalar nem desativar a administração. É o modo recomendado para controle
   parental sério (é como MDMs corporativos funcionam).

## Fluxo de uso

1. Instale o APK/AAB em cada celular.
2. Em **cada celular de filho**: escolha "Este é o celular do FILHO", conclua o
   setup (notificações → autorizar bloqueio de internet → ativar
   anti-desinstalação → desativar otimização de bateria). Um **código de
   pareamento** aparece na tela.
3. No **celular do responsável**: escolha "Este é o celular do RESPONSÁVEL". O app
   abre um **painel de filhos**. Para cada filho, informe um nome e o código
   exibido no celular dele e toque em "Conectar filho".
4. Toque em um filho no painel para editar as janelas de bloqueio dele ou usar
   "Bloquear agora". Cada filho tem sua **própria agenda**, aplicada de forma
   independente.

### Vários filhos, um só responsável

O modo responsável gerencia **quantos filhos você quiser**. Cada filho é um
documento próprio no Firestore (`families/{códigoDoFilho}`) e recebe apenas a
sua política — o celular de um filho nunca é afetado pela regra de outro.

### Remover a proteção / desinstalar

A proteção anti-desinstalação impede **a criança**, nunca o responsável. No
painel do pai, cada filho tem o botão **"Remover proteção / desinstalar"**:

1. Confirma na caixa de diálogo.
2. Com Firebase ativo, o comando chega ao celular do filho em tempo real; o app
   desativa o bloqueio, remove o Device Admin/Owner (`clearDeviceOwnerApp` +
   `removeActiveAdmin`, `setUninstallBlocked(false)`) e para o serviço.
3. Depois disso, o app pode ser **desinstalado normalmente**, inclusive no modo
   Device Owner — sem precisar de computador.

Sem Firebase (ou com o celular do filho offline), use o caminho manual:
Configurações → Segurança → Apps de administração → Morpheus → Desativar →
desinstalar. Em Device Owner sem rede, o último recurso é `adb shell dpm
remove-active-admin ...` ou reset de fábrica.

## Build local

Pré-requisitos: JDK 17 e Android SDK (via Android Studio ou `sdkmanager`).

```bash
./gradlew testDebugUnitTest      # testes da lógica de horário
./gradlew assembleDebug          # APK de debug
./gradlew bundleRelease          # AAB de release (precisa de keystore, veja abaixo)
```

### Assinatura (release)

Crie um `keystore.properties` na raiz (já ignorado pelo git):

```properties
storeFile=/caminho/para/release.jks
storePassword=***
keyAlias=***
keyPassword=***
```

Ou defina as variáveis `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
`KEY_PASSWORD` (é o que o CI usa).

## Controle remoto em tempo real (Firebase — opcional)

O app **compila e funciona sem Firebase** (a regra é aplicada localmente). Para o
responsável controlar o filho à distância:

1. Crie um projeto no [Firebase](https://console.firebase.google.com/), adicione
   um app Android com `applicationId` `com.morpheus.family`.
2. Ative **Cloud Firestore**, **Cloud Messaging** e, em **Authentication →
   Sign-in method**, o provedor **Anônimo** (o app faz login anônimo para as
   regras de segurança aceitarem as requisições).
3. Baixe o `google-services.json` e coloque em `app/google-services.json`
   (ignorado pelo git). O plugin do Google Services é aplicado automaticamente
   quando o arquivo existe.
4. Publique as regras de segurança do arquivo [`firestore.rules`](firestore.rules):
   ```bash
   firebase deploy --only firestore:rules
   ```
5. Estrutura no Firestore: um documento por filho em
   `families/{códigoDoFilho}` com os campos `scheduleJson` e
   `releaseRequestedAt`. O responsável escreve; cada celular de filho escuta o
   **seu** documento em tempo real (agenda + comando de remover proteção).

### Onde hospedar o Firebase e quanto custa

Você **não hospeda** o Firebase — ele é 100% gerenciado pelo Google, sem
servidor para manter. A "hospedagem" é a escolha do plano do projeto:

| Plano | Custo | Serve para |
| --- | --- | --- |
| **Spark (gratuito)** | **R$ 0** | Uso familiar. Firestore free: ~1 GiB armazenado, 50 mil leituras + 20 mil escritas **por dia**, Auth ilimitado. Sobra muito para alguns aparelhos. |
| **Blaze (pago por uso)** | Só paga o que exceder o free | Necessário apenas se um dia enviar push via Cloud Functions ou crescer muito. |

Recomendação: comece no **Spark (grátis)** — para controle parental de família
não há custo. Escolha a região do Firestore mais perto (ex.: `southamerica-east1`,
São Paulo) ao criar o banco, para menor latência.

## CI/CD → Play Store

O workflow [`.github/workflows/android.yml`](.github/workflows/android.yml):

- **A cada push/PR:** roda testes, lint e gera o APK de debug (artefato).
- **Em tags `v*` ou disparo manual:** gera o **AAB assinado** e faz **deploy na
  Play Store** via [`r0adkll/upload-google-play`](https://github.com/r0adkll/upload-google-play).

Para lançar uma versão:

```bash
git tag v1.0.0 && git push origin v1.0.0
```

### Secrets do repositório necessários

| Secret | Descrição |
| --- | --- |
| `KEYSTORE_BASE64` | Keystore de release em base64 (`base64 -w0 release.jks`). |
| `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` | Credenciais da keystore. |
| `PLAY_SERVICE_ACCOUNT_JSON` | JSON da conta de serviço com acesso à Play Console API. |
| `GOOGLE_SERVICES_JSON_BASE64` | (Opcional) `google-services.json` em base64, para builds com Firebase. |

> **Nota:** a primeira publicação de um app na Play Store precisa ser feita
> manualmente pelo Console. Depois disso, o CI atualiza as faixas
> (internal/alpha/beta/production) automaticamente.

## Estrutura

```
app/src/main/java/com/morpheus/family/
├── MorpheusApp.kt            # canais de notificação
├── admin/                    # Device Admin (anti-desinstalação) + ProtectionManager (release)
├── vpn/                      # VpnService de bloqueio
├── schedule/                 # motor de decisão + alarmes
├── service/                  # GuardianService (foreground + sync)
├── receiver/                 # boot + alarme de horário
├── remote/                   # Firebase (Firestore + FCM), opcional
├── data/                     # modo, Schedule, ChildRef, DataStore
└── ui/                       # Compose: seleção de modo, filho, painel do responsável
```

## Referência

O CI/CD e o fluxo de publicação na Play Store seguem o padrão do app
[`gps_speed`](https://github.com/tenoriodouglas/gps_speed) (deploy via
`r0adkll/upload-google-play`, keystore em base64, AAB assinado como artifact).
O `gps_speed` é em Flutter; o Morpheus é **Kotlin nativo** porque o motor de
bloqueio depende de APIs de sistema (VpnService, Device Admin) que são nativas —
o que também o mantém leve.
