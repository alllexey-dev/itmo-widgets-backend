package dev.alllexey.itmowidgets.backend.controllers

import dev.alllexey.itmowidgets.backend.services.FriendService
import dev.alllexey.itmowidgets.core.model.UserData
import dev.alllexey.itmowidgets.core.model.ApiResponse
import dev.alllexey.itmowidgets.backend.model.User.Companion.toDto
import dev.alllexey.itmowidgets.backend.repositories.UserRepository
import dev.alllexey.itmowidgets.backend.services.UserDetailsServiceImpl.Companion.uuid
import dev.alllexey.itmowidgets.backend.services.UserService
import dev.alllexey.itmowidgets.core.model.FriendRequest
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/friends")
class FriendController(
    private val friendService: FriendService,
    private val userService: UserService,
    private val userRepository: UserRepository,
) {

    @PostMapping("/add")
    fun addFriend(@RequestBody request: FriendRequest, authentication: Authentication): ApiResponse<String> {
        val user = userService.findUserById(authentication.uuid())
        friendService.sendRequest(user.isu, request.isu)
        return ApiResponse.success("Request sent")
    }

    @PostMapping("/remove")
    fun removeFriend(@RequestBody request: FriendRequest, authentication: Authentication): ApiResponse<String> {
        val user = userService.findUserById(authentication.uuid())
        friendService.cancelRequest(user.isu, request.isu)
        return ApiResponse.success("Friend removed")
    }

    @GetMapping("/get")
    fun myFriends(authentication: Authentication): ApiResponse<List<UserData>> {
        val user = userService.findUserById(authentication.uuid())

        val friendsIsu = friendService.getFriends(user.isu)
        val users = userRepository.findAllByIsuIn(friendsIsu)

        return ApiResponse.success(users.map { it.toDto() })
    }

    @GetMapping("/requests/incoming")
    fun incomingFriendRequests(authentication: Authentication): ApiResponse<List<UserData>> {
        val user = userService.findUserById(authentication.uuid())

        val incomingIsu = friendService.getIncomingRequests(user.isu)
        val users = userRepository.findAllByIsuIn(incomingIsu)

        return ApiResponse.success(users.map { it.toDto() })
    }

    @GetMapping("/requests/outgoing")
    fun outgoingFriendRequests(authentication: Authentication): ApiResponse<List<UserData>> {
        val user = userService.findUserById(authentication.uuid())
        val outgoingIsu = friendService.getOutgoingRequests(user.isu)
        val users = userRepository.findAllByIsuIn(outgoingIsu)

        return ApiResponse.success(users.map { it.toDto() })
    }
}