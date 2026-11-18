# Código reconstruído — Golden Face Recognition

Esta pasta contém uma **reconstrução experimental** publicada para consulta, inspeção e correções. Ela foi montada a partir de materiais de desenvolvimento; **não foi comprovado que corresponda ao APK disponibilizado nas releases**, nem foi feita uma comparação completa do APK ou de sua assinatura. Não é um projeto pronto para compilar ou funcionar normalmente.

## O que falta

- O modelo de reconhecimento `mobile_face_net.tflite` não está incluído: a procedência e os direitos dos pesos ainda precisam ser esclarecidos. Sem ele, o reconhecimento não funciona e o aplicativo pode falhar.
- As imagens PNG e o arquivo binário `gradle/wrapper/gradle-wrapper.jar` foram omitidos enquanto se resolvem a revisão de direitos dos recursos e/ou o envio dos binários. Os recursos de imagem ausentes e o wrapper incompleto impedem a compilação normal; o modelo ausente impede o funcionamento normal do reconhecimento.
- Não há APK, modelo, imagens ou outros binários nesta pasta. Não há garantia de build funcional; ainda são necessários montagem, dependências e testes em dispositivo.

Os arquivos foram mantidos nos caminhos da reconstrução, com seus cabeçalhos de licença originais. Alguns componentes e dependências podem ter termos próprios; a situação dos direitos deve ser verificada individualmente. Este aviso não atribui uma licença geral ao projeto.

## Créditos

- Projeto original de **Atharva Kale**: [Real-Time Face Recognition Android](https://github.com/atharvakale31/Real-Time_Face_Recognition_Android).
- Adaptações e desenvolvimento do Golden Face: **Alex e Toqi**.

A reconstrução é fornecida para facilitar a análise e a colaboração; funcionalidades específicas ainda precisam de validação em Android.
