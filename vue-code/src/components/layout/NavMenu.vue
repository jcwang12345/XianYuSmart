<script setup lang="ts">
/**
 * 导航菜单组件 - 电脑端侧边栏和手机端抽屉共用
 */
import { computed, markRaw, type Component } from 'vue'
import { hasPermission, isPlatformAdmin, isTenantManager, permissionState } from '@/utils/permission'
import { MENU_GROUPS, normalizeMenuLayout, type MenuIcon, type MenuItemDefinition } from '@/config/menu'
import IconAlert from '@/components/icons/IconAlert.vue'
import IconChart from '@/components/icons/IconChart.vue'
import IconClipboard from '@/components/icons/IconClipboard.vue'
import IconKey from '@/components/icons/IconKey.vue'
import IconLink from '@/components/icons/IconLink.vue'
import IconLog from '@/components/icons/IconLog.vue'
import IconMessage from '@/components/icons/IconMessage.vue'
import IconPackage from '@/components/icons/IconPackage.vue'
import IconPlus from '@/components/icons/IconPlus.vue'
import IconRobot from '@/components/icons/IconRobot.vue'
import IconSearch from '@/components/icons/IconSearch.vue'
import IconSend from '@/components/icons/IconSend.vue'
import IconShield from '@/components/icons/IconShield.vue'
import IconShoppingBag from '@/components/icons/IconShoppingBag.vue'
import IconText from '@/components/icons/IconText.vue'
import IconTooling from '@/components/icons/IconTooling.vue'
import IconTruck from '@/components/icons/IconTruck.vue'
import IconUser from '@/components/icons/IconUser.vue'
import IconUsers from '@/components/icons/IconUsers.vue'

interface VisibleMenuItem extends MenuItemDefinition {
  iconComponent: Component
}

interface VisibleMenuGroup {
  id: string
  label: string
  items: VisibleMenuItem[]
}

const emit = defineEmits<{
  select: [index: string]
}>()

const iconComponents: Record<MenuIcon, Component> = {
  alert: markRaw(IconAlert),
  chart: markRaw(IconChart),
  clipboard: markRaw(IconClipboard),
  dashboard: markRaw(IconChart),
  goods: markRaw(IconShoppingBag),
  key: markRaw(IconKey),
  link: markRaw(IconLink),
  log: markRaw(IconLog),
  message: markRaw(IconMessage),
  package: markRaw(IconPackage),
  plus: markRaw(IconPlus),
  robot: markRaw(IconRobot),
  search: markRaw(IconSearch),
  send: markRaw(IconSend),
  shield: markRaw(IconShield),
  text: markRaw(IconText),
  tooling: markRaw(IconTooling),
  truck: markRaw(IconTruck),
  user: markRaw(IconUser),
  users: markRaw(IconUsers)
}

const menuGroupMap = new Map(MENU_GROUPS.map(group => [group.id, group]))

const visibleGroups = computed<VisibleMenuGroup[]>(() => {
  const layout = normalizeMenuLayout(permissionState.value?.menuLayout)
  return layout.groups.flatMap(groupLayout => {
    const definition = menuGroupMap.get(groupLayout.id)
    if (!definition) return []

    const items = groupLayout.items.flatMap(itemId => {
      const item = definition.items.find(menuItem => menuItem.id === itemId)
      if (!item || item.adminOnly && !isPlatformAdmin.value
        || item.managerOnly && !isTenantManager.value
        || item.permission && !hasPermission(item.permission)) {
        return []
      }
      return [{ ...item, iconComponent: iconComponents[item.icon] }]
    })
    return items.length ? [{ id: definition.id, label: definition.label, items }] : []
  })
})

const onSelect = (index: string) => {
  emit('select', index)
}
</script>

<template>
  <nav class="nav-menu" aria-label="主导航">
    <!-- pending-orders hidden -->
    <TransitionGroup name="nav-group" tag="div" class="nav-menu-groups">
      <section v-for="group in visibleGroups" :key="group.id" class="nav-menu-group">
        <div class="nav-menu-group-title">{{ group.label }}</div>
        <TransitionGroup name="nav-item" tag="div" class="nav-menu-list">
          <router-link
            v-for="item in group.items"
            :key="item.id"
            :to="item.path"
            class="nav-menu-item"
            active-class="nav-menu-item--active"
            @click="onSelect(item.path)"
          >
            <span class="nav-menu-icon">
              <component :is="item.iconComponent" />
            </span>
            <span class="nav-menu-label">{{ item.label }}</span>
          </router-link>
        </TransitionGroup>
      </section>
    </TransitionGroup>
  </nav>
</template>

<style scoped>
.nav-menu {
  border-right: none;
  background: transparent;
  padding: 4px 12px 20px;
}

.nav-menu-groups {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.nav-menu-group {
  min-width: 0;
}

.nav-menu-group-title {
  padding: 0 12px 5px;
  color: #908c82;
  font-size: 11px;
  font-weight: 600;
  line-height: 20px;
  letter-spacing: .06em;
}

.nav-menu-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.nav-menu-item {
  position: relative;
  display: flex;
  align-items: center;
  min-width: 0;
  height: 42px;
  padding: 0 11px;
  border-radius: 11px;
  color: #625f58;
  text-decoration: none;
  transition: color 180ms ease, background-color 180ms ease, box-shadow 180ms ease;
}

.nav-menu-item::before {
  position: absolute;
  top: 10px;
  bottom: 10px;
  left: -4px;
  width: 3px;
  border-radius: 3px;
  background: #e0a400;
  content: '';
  opacity: 0;
  transform: scaleY(.45);
  transition: opacity 180ms ease, transform 180ms ease;
}

.nav-menu-item:hover {
  color: var(--xy-ink);
  background: #faf5df;
}

.nav-menu-item--active {
  color: var(--xy-ink);
  background: linear-gradient(90deg, var(--xy-yellow-soft), #fffaf0);
  box-shadow: inset 0 0 0 1px rgba(226, 169, 0, .18);
  font-weight: 700;
}

.nav-menu-item--active::before {
  opacity: 1;
  transform: scaleY(1);
}

.nav-menu-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  margin-right: 8px;
  border-radius: 7px;
  color: #77736b;
  flex-shrink: 0;
  transition: color 180ms ease, background-color 180ms ease;
}

.nav-menu-icon :deep(svg) {
  width: 17px;
  height: 17px;
}

.nav-menu-item:hover .nav-menu-icon {
  color: var(--xy-ink);
  background: rgba(255, 255, 255, .7);
}

.nav-menu-item--active .nav-menu-icon {
  color: var(--xy-ink);
  background: var(--xy-yellow);
}

.nav-menu-label {
  min-width: 0;
  overflow: hidden;
  font-size: 14px;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.nav-group-move,
.nav-item-move {
  transition: transform 240ms cubic-bezier(.2, .8, .2, 1);
}

.nav-group-enter-active,
.nav-group-leave-active,
.nav-item-enter-active,
.nav-item-leave-active {
  transition: opacity 160ms ease, transform 160ms ease;
}

.nav-group-enter-from,
.nav-group-leave-to,
.nav-item-enter-from,
.nav-item-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

@media (prefers-reduced-motion: reduce) {
  .nav-menu-item,
  .nav-menu-item::before,
  .nav-menu-icon,
  .nav-group-move,
  .nav-item-move,
  .nav-group-enter-active,
  .nav-group-leave-active,
  .nav-item-enter-active,
  .nav-item-leave-active {
    transition: none;
  }
}
</style>
