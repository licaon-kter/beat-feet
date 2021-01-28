package com.serwylo.beatgame.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.math.Vector3
import com.serwylo.beatgame.BeatGame
import com.serwylo.beatgame.Globals
import com.serwylo.beatgame.HUD
import com.serwylo.beatgame.audio.features.Feature
import com.serwylo.beatgame.audio.features.World
import com.serwylo.beatgame.entities.*
import com.serwylo.beatgame.graphics.calcDensityScaleFactor
import com.serwylo.beatgame.graphics.makeCamera


class PlatformGameScreen(
        private val game: BeatGame,
        private val world: World
) : ScreenAdapter() {

    private val camera = makeCamera(20, 10, calcDensityScaleFactor())
    private lateinit var hud: HUD
    private val obstacles = mutableListOf<Obstacle>()

    private lateinit var ground: Ground
    private lateinit var player: Player
    private lateinit var deadPlayer: DeadPlayer
    private lateinit var successPlayer: SuccessPlayer

    private var isInitialised = false

    private var atlas: TextureAtlas? = null

    private var state = State.PENDING
    private var startTime = 0f
    private var playTime = 0f
    private var deathTime = 0f
    private var winTime = 0f

    private var prePauseState: State = state

    enum class State {
        PENDING,
        PAUSED,
        WARMING_UP,
        PLAYING,
        DYING,
        WINNING,
    }

    override fun show() {

        isInitialised = false

        atlas = TextureAtlas(Gdx.files.internal("sprites.atlas"))

        val allFeatures = mutableListOf<Feature>()
        allFeatures.addAll(world.featuresLow)
        allFeatures.addAll(world.featuresMid)
        allFeatures.addAll(world.featuresHigh)

        /*
        obstacles.addAll(generateObstacles(atlas!!, world.featuresLow))
        obstacles.addAll(generateObstacles(atlas!!, world.featuresMid))
        obstacles.addAll(generateObstacles(atlas!!, world.featuresHigh))
        obstacles.sortBy { it.rect.x }
         */

        obstacles.addAll(generateObstacles(atlas!!, allFeatures))

        Gdx.input.setCatchKey(Input.Keys.BACK, true)
        Gdx.input.inputProcessor = object : InputAdapter() {

            override fun keyDown(keycode: Int): Boolean {
                if (keycode == Input.Keys.SPACE) {
                    if (state == State.PAUSED) {
                        resume()
                    } else {
                        player.performJump()
                    }
                    return true
                } else if (keycode == Input.Keys.ESCAPE || keycode == Input.Keys.BACK) {
                    game.showMenu()
                    return true
                } else if (keycode == Input.Keys.P) {
                    if (state == State.PAUSED) {
                        resume()
                    } else {
                        pause()
                    }
                    return true
                }

                return false
            }

            override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
                player.performJump()
                return true
            }
        }

        hud = HUD(atlas!!)

        camera.translate(camera.viewportWidth / 4, camera.viewportHeight / 5, 0f)
        camera.update()

        player = Player(Vector2(SCALE_X, 0f), atlas!!)
        deadPlayer = DeadPlayer(atlas!!)
        successPlayer = SuccessPlayer(atlas!!)

        ground = ObstacleBuilder.makeGround(atlas!!)

        Globals.animationTimer = 0f

        isInitialised = true
    }

    override fun hide() {

        world.dispose()

        atlas?.dispose()
        atlas = null

        hud.dispose()

        Gdx.input.inputProcessor = null
        Gdx.input.setCatchKey(Input.Keys.BACK, false)
    }

    override fun render(delta: Float) {
        if (!isInitialised) {
            return
        }

        Globals.animationTimer += delta
        if (state == State.PLAYING) {
            playTime += delta
        }

        processInput()
        updateEntities(delta)
        renderEntities(delta)

        hud.render((playTime / world.duration).coerceAtMost(1f), player)
    }

    private fun processInput() {
        if (Gdx.input.isKeyJustPressed(Input.Keys.SPACE) || Gdx.input.isButtonJustPressed(Input.Buttons.LEFT)) {
            if (state == State.PENDING) {
                state = State.WARMING_UP
                startTime = Globals.animationTimer
            } else if (state == State.PLAYING || state == State.WARMING_UP) {
                player.performJump()
            }
        }
    }

    private fun updateEntities(delta: Float) {

        checkCollisions()

        if (state == State.PENDING || state == State.PAUSED) {

            // Do nothing, we just need to animate the running player (which happens in the render loop).
            return

        }

        if (state == State.PLAYING || state == State.WARMING_UP) {

            player.update(delta)
            scrollCamera(delta)
            shakeCamera(delta)

            if (state == State.WARMING_UP && Globals.animationTimer - startTime > WARM_UP_TIME) {
                startGame()
            }

            if (player.getHealth() <= 0) {

                state = State.DYING
                deadPlayer.setup(player.position)
                deathTime = Globals.animationTimer

            }

            if (player.position.x >= (world.duration + WARM_UP_TIME + END_LEVEL_WALK_TIME) * SCALE_X) {

                state = State.WINNING
                successPlayer.setup(player.position)
                winTime = Globals.animationTimer

            }

        } else if (state == State.DYING) {

            if (Globals.animationTimer - deathTime < DEATH_TIME) {

                camera.translate(0f, delta * DeadPlayer.FLOAT_SPEED / 8)
                camera.zoom += DEATH_ZOOM_RATE * delta
                camera.update()

            } else {

                endGame()

            }

        } else if (state == State.WINNING) {

            if (Globals.animationTimer - winTime > WINNING_TIME) {

                endGame()

            }
        }

    }

    private var cameraShakeYPosition = 0f
    private var cameraShakeTotalDuration = 0f
    private var cameraShakeCurrentDuration = 0f
    private var cameraShakeAmplitude = 0f

    private fun shakeCamera(delta: Float) {

        if (player.justHitDamage > 0 && cameraShakeTotalDuration <= 0) {
            cameraShakeTotalDuration = CAMERA_SHAKE_DURATION
            cameraShakeAmplitude = player.justHitDamage.toFloat().coerceAtMost(CAMERA_SHAKE_MAX_DAMAGE) / CAMERA_SHAKE_MAX_DAMAGE * CAMERA_SHAKE_MAX_DISTANCE
            Gdx.input.vibrate((CAMERA_SHAKE_DURATION * 1000 * cameraShakeAmplitude * 4).toInt())
        }

        if (cameraShakeTotalDuration <= 0) {
            return
        }

        cameraShakeCurrentDuration += delta

        if (cameraShakeCurrentDuration >= cameraShakeTotalDuration) {

            camera.translate(0f, -cameraShakeYPosition)
            cameraShakeTotalDuration = 0f
            cameraShakeCurrentDuration = 0f
            cameraShakeYPosition = 0f

        } else {

            val factor = cameraShakeCurrentDuration / cameraShakeTotalDuration
            val radians = factor * Math.PI * 2
            val desiredPosition = (Math.sin(radians) * cameraShakeAmplitude - cameraShakeAmplitude / 2).toFloat()
            val shift = desiredPosition - cameraShakeYPosition

            camera.translate(0f, shift.toFloat())
            cameraShakeYPosition += shift

        }

        camera.update()
    }

    private fun scrollCamera(delta: Float) {
        camera.translate(delta * SCALE_X, 0f)
        camera.update()
    }

    /**
     * Not perfect, but a minor heuristic to help cull items that don't need to be rendered.
     * We'll keep a rough idea of what is off to the left of the screen, but always render 20 items
     * to the left of this object. That is because some objects are wider than others, and we
     * can't just say "Item x is off the screen, so all items before it are also off the screen".
     */
    private var leftMostObstacleOnScreenIndex = 0

    private fun renderEntities(delta: Float) {
        Gdx.gl.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        ground.render(camera, state == State.PAUSED)
        val cameraRight = camera.unproject(Vector3(Gdx.graphics.width.toFloat(), 0f, 0f)).x
        for (i in (leftMostObstacleOnScreenIndex - 20).coerceAtLeast(0) until obstacles.size) {
            val obstacle = obstacles[i]
            if (obstacle.rect.x > cameraRight) {
                break;
            }

            obstacle.render(camera, state == State.PAUSED)
        }

        if (state == State.DYING) {
            deadPlayer.render(camera, state == State.PAUSED)
        } else if (state == State.WINNING) {
            successPlayer.render(camera, state == State.PAUSED)
        } else {
            player.render(camera, state == State.PAUSED)
        }
    }

    private fun startGame() {
        state = State.PLAYING
        world.music.play()
    }

    private fun endGame() {
        world.music.stop()
        game.endGame(world, player.getScore(), (playTime / world.duration).coerceAtMost(1f))
    }

    override fun pause() {
        super.pause()

        prePauseState = state
        world.music.pause()
        state = State.PAUSED
    }

    override fun resume() {
        super.resume()

        world.music.play()
        state = prePauseState
    }

    private fun checkCollisions() {
        player.clearHit()

        val cameraRight = camera.unproject(Vector3(Gdx.graphics.width.toFloat(), 0f, 0f)).x
        val cameraLeft = camera.unproject(Vector3(0f, 0f, 0f)).x

        for (i in (leftMostObstacleOnScreenIndex - 20).coerceAtLeast(0) until obstacles.size) {
            val obstacle = obstacles[i]
            if (obstacle.rect.x > cameraRight) {
                break;
            }

            if (obstacle.rect.x + obstacle.rect.width < cameraLeft) {
                leftMostObstacleOnScreenIndex = i + 1
            }

            if (player.isColliding(obstacle.rect)) {
                player.hit(obstacle)
            }
        }
    }

    companion object {

        /**
         * To convert horizontal units from seconds -> metres. That sounds a bit odd, but this is a side
         * scrolling game where features appear at very specific time points, and the screen scrolls
         * at a consistent rate. Therefore it does kind-of-in-an-odd-way make sense to multiple a seconds
         * value to get a horizontal offset in metres.
         *
         * All of the level generation starts with music, which is measured in samples at a particular
         * sample rate.
         *
         * This is then converted into specific time points in seconds, so that regardless of the sample
         * rate of a particular song, all songs produce features of the same duration.
         *
         * The final step is to convert seconds into measurements on screen. This is used for that.
         */
        const val SCALE_X = 5f

        /**
         * Less than this distance between obstacles, and we will merge them together (i.e. increase
         * the size of the one on the left until it reaches the one on the right).
         */
        private const val OBSTACLE_GAP_THRESHOLD = 0.175f

        /**
         * The features extracted from audio line up with exactly when a particular feature of the
         * music is detected. The game is more fun when this lines up with when you'd expect the
         * player to have to jump in order to avoid the feature (more rhythmic that way), so we
         * offset each feature by this many seconds.
         */
        private const val FEATURE_START_TIME_OFFSET = -0f

        /**
         * Once the game starts, the player runs infinitely until the player jumps for the first
         * time. After that, wait this long before starting the song.
         */
        private const val WARM_UP_TIME = 3f

        /**
         * How long after dying before we move onto the game end screen.
         * The animation of death is handled differently, managed by the [Player] class.
         */
        private const val DEATH_TIME = 5f

        /**
         * Play a celebration animation for this long.
         */
        private const val WINNING_TIME = 3f

        /**
         * After reaching 100% of the level, walk for this much longer before stopping, celebrating, then ending the level.
         */
        private const val END_LEVEL_WALK_TIME = 2f

        private const val DEATH_ZOOM_RATE = -0.015f

        /**
         * When you hit a really big obstacle, shake the camera this many units up and down.
         */
        private const val CAMERA_SHAKE_MAX_DISTANCE = 0.1f

        /**
         * This amount of damage in one go will result in the maximum shake, anything above will
         * still cause the same amount of shaking, anything below will result in a smaller shake.
         */
        private const val CAMERA_SHAKE_MAX_DAMAGE = Player.AREA_TO_DAMAGE

        private const val CAMERA_SHAKE_DURATION = 0.12f

        private fun generateObstacles(atlas: TextureAtlas, features: List<Feature>): List<Obstacle> {

            val rects = features.sortedBy { it.startTimeInSeconds }.map {

                // Round to the nearest tile if it is above a single tile high.
                // If it is below a tile in size, that is okay, because there are a number of obstacles
                // which are sub-one-tile large.
                val rawHeight = (it.strength * Obstacle.STRENGTH_TO_HEIGHT).coerceAtLeast(Obstacle.MIN_HEIGHT)
                val roundedHeight = ObstacleBuilder.roundToNearestTile(rawHeight)

                val rawWidth = it.durationInSeconds * SCALE_X
                val roundedWidth = ObstacleBuilder.roundToNearestTile(rawWidth)

                val isSingleTile = rawHeight < ObstacleBuilder.TILE_SIZE && rawWidth < ObstacleBuilder.TILE_SIZE

                Rectangle(
                        (it.startTimeInSeconds + FEATURE_START_TIME_OFFSET + WARM_UP_TIME) * SCALE_X,
                        0f,
                        if (isSingleTile) { rawWidth } else { roundedWidth },
                        if (isSingleTile) { rawHeight } else { roundedHeight }
                )

            }

            val toRemoveIndices = mutableSetOf<Int>()
            var i = 0
            while (i < rects.size - 1) {

                if (toRemoveIndices.contains(i)) {
                    i++
                    continue
                }

                val current = rects[i]

                // Continue merging subsequent items in succession if they are of the same height
                // and close enough x distance.
                var nextIndex = i + 1
                while (nextIndex < rects.size) {

                    // If this was removed as part of a (slightly) earlier obstacle comparison.
                    if (toRemoveIndices.contains(i)) {
                        nextIndex ++
                        continue
                    }

                    val next = rects[nextIndex]

                    val distanceToNext = next.x - current.x - current.width
                    if (distanceToNext > OBSTACLE_GAP_THRESHOLD) {
                        break;
                    }

                    if (current.height == next.height) {

                        // Consecutive items of more or less the same height should join together
                        // into larger buildings.
                        current.width += distanceToNext + next.width
                        toRemoveIndices.add(nextIndex)

                    } else if (current.width <= ObstacleBuilder.TILE_SIZE && next.width <= ObstacleBuilder.TILE_SIZE) {
                        // Two or more narrow lights next to each other (regardless of height) tend not to look good together.
                        // TODO: Ideally, we'd keep the tallest of these.
                        toRemoveIndices.add(nextIndex)
                    }

                    nextIndex ++

                }

                i ++
            }

            return rects.filterIndexed { index, _ -> !toRemoveIndices.contains(index) }
                    .map { ObstacleBuilder.makeObstacle(it, atlas) }

        }

    }

}