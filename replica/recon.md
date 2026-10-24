# Vitral — recon

Referência: a parte de instalação de instaladores de tema da comunidade (ChaoMe Theme Editor, HyperOS MTZ Studio). Só documentação pública foi lida.

## O que se aprendeu
- No app Temas moderno (Global 3.0.6.8+ e China 10.8+), a antiga tela `ApplyThemeForScreenshot` foi removida. Shizuku em modo ADB (UID 2000) não reabre telas que não existem.
- Sem root, o caminho que funciona no Poco X7 Pro (HyperOS 3) é o "truque do backup": criar um backup no Temas, trocar o arquivo `.mtz` do backup pelo tema novo e aplicar o backup.
- O shell do Shizuku consegue gravar em `/sdcard/Android/data/com.android.thememanager/files/MIUI/theme`.

## Fluxo da v1
1. Shizuku conectado (guia passo a passo se não estiver).
2. Escolher o `.mtz` (seletor ou "Abrir com").
3. Conferir o arquivo (description.xml, módulos, prévia).
4. Escolher o backup (o mais recente vem marcado).
5. Copiar o original para `Download/Vitral/backups`, gravar o tema novo por cima, conferir o tamanho, reiniciar o Temas.
6. Abrir o Temas para o usuário tocar em Aplicar.
7. Restaurar qualquer cópia original com um toque.

Tamanho: pequeno.
