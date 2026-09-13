// 扩展名不能省:本文件要能被 `node --test` 直接加载(Node 的 ESM 解析器不做扩展名补全,
// 而 Vite 会)。凡是进测试的模块都遵守这一条 —— 仓库其余地方的省略写法属于打包器。）
import { avatarSrc } from './avatar.js'

/**
 * 一个人怎么被显示 —— 名字与头像。
 *
 * <p>「昵称,没有就用用户名」这条规则此前在 9 个 module 里重复了约 20 次;而
 * `avatarSrc(url, nickname || username)` 这个组合本身也重复了 7 次 —— 于是
 * 「头像拿谁的名字做兜底」这件事有 7 份各自的答案。
 *
 * <p>两者缺一不可:名字为空时,`avatarSrc` 只能退到那个 `?` 占位图;
 * 而头像的 dicebear 规则(见 `avatar.js`)也依赖同一个名字来生成确定性颜色。
 * 放在一起,「显示一个人」才是一个可以被问一次的问题。
 */

/**
 * 显示名:昵称优先,其次用户名。
 *
 * <p>两者都没有时返回空串 —— **不编一个名字**。作者行确实可能缺失
 * (见 `ArticleListItems` 在作者查不到时留 `null`),那时界面留白,
 * 而不是显示一个数据库里不存在的名字。
 */
export function displayName(person) {
  return person?.nickname || person?.username || ''
}

/** 头像 URL:给他这个人;dicebear 之类的占位地址会被换成确定性的首字母图。 */
export function displayAvatar(person) {
  return avatarSrc(person?.avatar, displayName(person))
}
