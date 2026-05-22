class Stack<T> {
    private val items = mutableListOf<T>()

    fun push(item: T) {
        items.add(item)
    }

    fun pop(): T {
        if (items.isEmpty()) error("Stack is empty")
        return items.removeAt(items.size - 1)
    }

    fun peek(): T {
        if (items.isEmpty()) error("Stack is empty")
        return items[0]
    }

    fun isEmpty(): Boolean = items.isEmpty()

    fun size(): Int = items.size
}
