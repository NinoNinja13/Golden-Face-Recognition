# Integração com o MacroDroid

Esta página descreve a integração **por notificações** identificada na variante integrada em revisão. O formato das notificações foi conferido no código; os exemplos de macro são orientações para configurar e testar, não uma declaração de teste completo em todos os aparelhos. O código e o APK dessa variante ainda não estão disponíveis neste repositório.

## Dados enviados pela notificação

Com o aplicativo em português, o código prepara estas notificações:

| Situação | Título | Texto |
| --- | --- | --- |
| Rosto reconhecido com o nome cadastrado `Lia` | `Golden Face: rosto reconhecido` | `Lia` |
| Sem reconhecimento | `Golden Face: sem reconhecimento` | `Sem reconhecimento` |

`Lia` é apenas um nome fictício para ilustrar. Com o aplicativo em inglês, os títulos são `Golden Face: face recognized` e `Golden Face: no recognition`; o texto sem reconhecimento é `No recognition`. O nome reconhecido é o texto da notificação, não uma fotografia nem um vetor facial.

O código publica ao iniciar o estado ou quando o nome/estado muda; não fica reenviando periodicamente um nome que não mudou. Se a análise parar por cerca de três segundos, o estado pode mudar para sem reconhecimento. O app também pode limpar a notificação ao encerrar ou transferir a câmera. Por isso, não trate ausência de uma nova notificação como confirmação de que a pessoa ainda está diante da câmera.

## Exemplo: agir ao reconhecer um nome

1. No Android, conceda ao MacroDroid acesso às notificações. No Golden Face, permita câmera e notificações quando necessário e mantenha o reconhecimento ativo.
2. Crie uma macro com o gatilho **Notificação recebida** e selecione o aplicativo Golden Face correspondente à instalação em uso.
3. No filtro, use a opção de separar título e mensagem, quando disponível. Para a interface em português, configure o título para corresponder a `Golden Face: rosto reconhecido` e o texto para corresponder a `Lia` (substitua pelo nome exatamente como cadastrado).
4. Adicione a ação desejada, por exemplo exibir uma mensagem de boas-vindas. Salve e teste com um rosto cadastrado e com outro nome para conferir o filtro.

Para tratar qualquer nome, filtre apenas o título de reconhecimento e use o texto mágico `{notification}` em uma ação. Exemplo de mensagem: `Olá, {notification}!`. O MacroDroid também disponibiliza `{not_title}` para o título; os nomes das opções podem variar por versão ou idioma do app. Referência: [gatilho de notificação do MacroDroid](https://macrodroidforum.com/wiki/index.php?title=Trigger%3A_Notification).

## Exemplo: quando não há reconhecimento

Crie outra macro com o mesmo gatilho e aplicativo, filtrando o título `Golden Face: sem reconhecimento`. Uma ação pode registrar ou mostrar `Sem reconhecimento`. Isso não significa necessariamente que não há ninguém: pode haver um rosto desconhecido, uma perda de análise ou outra situação sem nome reconhecido. A limpeza da notificação é um evento diferente de receber a notificação de sem reconhecimento.

## Observações da variante revisada

- O canal de notificações de rosto tem ID `golden_face_names`; as notificações são silenciosas no código. A permissão de notificações e as configurações do aparelho podem afetar a entrega ao MacroDroid.
- O aplicativo mantém também uma notificação de serviço em segundo plano. Filtre pelo título de rosto para não confundi-las.
- Existe uma classe antiga de servidor local na árvore reconstruída, mas não foi encontrada uma chamada que a inicie na variante integrada; não conte com `localhost` como interface ativa. Também há uma constante de ação de broadcast, mas não foi encontrada emissão correspondente na integração revisada. A interface documentada aqui é a de notificações.
- A integração precisa ser conferida no aparelho, inclusive após mudanças de idioma, permissões, versão do Android ou configuração de segundo plano.
