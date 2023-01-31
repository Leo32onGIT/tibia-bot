# Discord Bot for Tibia
   
Current features include:    
- Online List
- Levels List
- Deaths List

### Online player list    

> ![examples](https://i.imgur.com/O4lnDXt.png)

### Levels List

> ![examples](https://i.imgur.com/OEnUbIJ.png)

### Deaths list    
  
  `no color` = neutral pve    
  `white` = neutral pvp    
  `red` = ally    
  `green` = hunted    
  `purple` = rare boss (this pokes)
  
> ![examples](https://i.imgur.com/09xAyde.gif)

It will poke a [discord role](https://github.com/Leo32onGIT/death-tracker/blob/main/death-tracker/src/main/resources/application.conf#L23) if someone dies to a [tracked monster](https://github.com/Leo32onGIT/death-tracker/blob/main/death-tracker/src/main/resources/application.conf#L24-L94).

> ![tracked boss](https://i.imgur.com/cbwovAO.png)

## Pre-requisites:

#### Create the new bot in Discord
1. Go to: https://discord.com/developers/applications and create a **New Application**.
2. Go to the **Bot** tab and click on **Add Bot**.
3. Click **Reset Token** & take note of the `Token` that is generated.
4. Scroll down and enable **Message Content Intent**.

#### Prepare your Discord for the bot
1. Create a new category with the name of the server this bot is for:    
`seanera`
2. Create the following channels in it:    
`allies`    
`neutrals`    
`enemies`    
`levels`    
`deaths`
3. Ensure the bot has the following permissions in these channels:    
`View Channel`, `Manage Channel`, `Send Messages`, `Manage Messages`, `Manage Webhooks`, `Read Message History`
4. Create a new category called:    
`configuration`
5. Create the following channels in it:   
`hunted-players`    
`hunted-guilds`    
`allied-players`    
`allied-guilds`

This is what it should look like:
> ![prep example](https://i.imgur.com/MMRIjys.png)

#### Custom Emojis and Poke Roles
The bot is configured to point to emojis and roles in _my_ discord server.     
You will need to change this to point to your emojis and your discord roles.

1. Open the [discord.conf](https://github.com/Leo32onGIT/death-tracker/blob/seanera/death-tracker/src/main/resources/discord.conf#L11-L34) file and edit it.
2. Point to `emoji ids` and `role ids` that exist on _your_ discord server.

#### Prepare your linux machine to host the bot
1. Ensure `docker` is installed.
1. Ensure `default-jre` is installed.
1. Ensure `sbt` is installed.

## Deployment Steps

1. Clone the repository to your host machine:    
`git clone https://github.com/Leo32onGIT/death-tracker.git`    
2. Open the [discord.conf](https://github.com/Leo32onGIT/death-tracker/blob/seanera/death-tracker/src/main/resources/discord.conf#L7) file and edit it.
3. Change `world-channels` to the server you wish to track (it is set to `wizera` by default).
4. Navigate to the folder that contains the main **build.sbt** file:    
`cd death-tracker`    
5. Compile the code into a docker image:    
`sbt docker:publishLocal`    
6. Take note of the docker \<image id\> you just created: `docker images`   
> ![docker image id](https://i.imgur.com/nXvSeIL.png)

7. Create a `prod.env` file with the discord server/channel id & bot authentication token:
> ```env
> TOKEN=XXXXXXXXXXXXXXXXXXXXXX   
> GUILD_ID=XXXXXXXXXXXXXXXXXXX   
> ```
8. Run the docker container you just created & parse the **prod.env** file:    
`docker run --rm -d --env-file prod.env <image_id>`  

## Debugging

1. If something isn't working correctly you should be able to see why very clearly in the logs.
2. Use `docker ps` to find the \<container id\> for the running bot.
3. Use `docker logs <container id>` to view the logs.
