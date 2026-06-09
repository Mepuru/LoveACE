import Foundation

@MainActor @Observable
final class ProfileViewModel {
    var nickname = ""
    var avatarURI: String?
    var homeImageURI: String?
    var laborImageURI: String?
    private let store = ProfileStore()

    func setActiveUserId(_ userId: String) {
        store.activeUserId = userId
        nickname = store.nickname
        avatarURI = store.avatarURI
        homeImageURI = store.homeImageURI
        laborImageURI = store.laborImageURI
    }

    func setNickname(_ name: String) { store.nickname = name; nickname = name }
    func setAvatarURI(_ uri: String?) { store.avatarURI = uri; avatarURI = uri }
    func setHomeImageURI(_ uri: String?) { store.homeImageURI = uri; homeImageURI = uri }
    func setLaborImageURI(_ uri: String?) { store.laborImageURI = uri; laborImageURI = uri }
}
