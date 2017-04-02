# Youtube downloader

Project that extends the program in the stackoverflow answer: 
http://stackoverflow.com/questions/4700945/youtube-data-api-get-access-to-media-stream-and-play-java/4834369#4834369

Link with Quality and Codecs: 
https://en.wikipedia.org/w/index.php?title=YouTube&oldid=461873899#Quality_and_codecs

## Build
``$mvn package``

## Run
``$java -jar target/youtube-downloader-jar-with-dependencies.jar OPTIONS``

## Examples

for I9OZQg4j6EI
* youtube id: I9OZQg4j6EI 
* output directory: out
* format mp4

``java -jar target/youtube-downloader-jar-with-dependencies.jar -dir out -format 18 I9OZQg4j6EI``

## Personal extensions to the original
* Package name change to net.runnerdave
* mavenized
* readme
* added new formats
* updated the file to use the current youtube api value of _url_encoded_fmt_stream_map_ instead of _fmt_url_map_

## Potential Gotchas
* the video requires a signature
    *  in the key value with urls to download: url_encoded_fmt_stream_map there is an encrytped
        signature, this needs to be decrypted and added to the original url,
        this is explained here: https://www.quora.com/How-can-I-make-a-YouTube-video-downloader-web-application-from-scratch