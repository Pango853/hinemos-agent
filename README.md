# Hinemos Agent Project

Hinemos Agenst source forked from  <https://osdn.net/projects/hinemos/>.


This branch, **grable**, is going to convert this existing project to build using gradle.

You have to build [manager side](https://github.com/pango853/hinemos-manager) jars at first.

Then run

```
> gradle build
```

And export jars as below,
```
> gradle export
```
`
After all you can replace the original file in the hinemos_agent lib directory with the newly generated build/libs/\*.jar

